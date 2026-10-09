package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test logika murni perintah Telegram (tanpa Android runtime). */
public class TgBotTest {


    @Test
    public void argumenVersiValid_terimaVersiDanTerbaru() {
        assertTrue(TgBot.argumenVersiValid(null));
        assertTrue(TgBot.argumenVersiValid(""));
        assertTrue(TgBot.argumenVersiValid("1.32.0"));
        assertTrue(TgBot.argumenVersiValid("v1.32.0"));
        assertTrue(TgBot.argumenVersiValid("terbaru"));
        assertTrue(TgBot.argumenVersiValid("latest"));
        assertFalse(TgBot.argumenVersiValid("abc"));
        assertFalse(TgBot.argumenVersiValid("1.32.x"));
    }

    @Test
    public void sidikMenu_stabilDanUnikPerToken() {
        String a1 = TgBot.sidikMenu("123:ABC");
        String a2 = TgBot.sidikMenu("  123:ABC  ");
        String b = TgBot.sidikMenu("123:ABD");
        assertEquals(64, a1.length());
        assertTrue(a1.matches("[0-9a-f]+"));
        assertEquals(a1, a2);
        assertFalse(a1.equals(b));
        assertEquals("", TgBot.sidikMenu(null));
        assertEquals("", TgBot.sidikMenu("   "));
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
    public void mundurTakMenurunkanTandaAir() {
        long maks = 1_700_000_000_000L;
        TgBot.catatWall(maks);
        long mundur = maks - 3600_000L;
        assertTrue(TgBot.jamMundur(mundur));
        TgBot.catatMundurDanBolehIngatkan(mundur);
        // Tanda air bertahan: rollback yang sama tetap terdeteksi (tak pulih
        // palsu), dan jam normal tak dianggap mundur.
        assertTrue(TgBot.jamMundur(mundur));
        assertFalse(TgBot.jamMundur(maks));
        TgBot.catatWall(maks);
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
        assertTrue(TgBot.isRestoreConfirm("confirm"));
        assertTrue(TgBot.isRestoreConfirm(" Konfirmasi "));
    }

    @Test
    public void menuPayload_memuatSemuaPerintah() {
        String json = TgBot.menuPayload();
        assertTrue(json.startsWith("{\"commands\":["));
        for (String c : new String[]{"status", "log", "uptime", "alive", "backup",
                "restore", "ca", "cabackup", "careset", "crashlog", "versi", "update", "webvault", "start", "stop",
                "restart", "help"}) {
            assertTrue("hilang: " + c, json.contains("\"" + c + "\""));
        }
        assertEquals(17, TgBot.daftarPerintahMenu().length);
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
                "/restore", "/ca", "/cabackup", "/careset", "/crashlog", "/versi", "/update", "/webvault", "/start", "/stop",
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
    public void ambilArgumen_pisahWhitespaceApapun() {
        assertEquals("123456", TgBot.ambilArgumen("/stop 123456"));
        assertEquals("123456", TgBot.ambilArgumen("/stop\t123456"));
        assertEquals("123456", TgBot.ambilArgumen("/stop@BotName\t123456"));
        assertEquals("YA", TgBot.ambilArgumen("/restore  YA "));
        assertEquals("", TgBot.ambilArgumen("/status"));
        assertEquals("", TgBot.ambilArgumen("   "));
        assertEquals("", TgBot.ambilArgumen(null));
    }

    @Test
    public void callbackDataValid_hanyaPerintahDikenal() {
        assertTrue(TgBot.callbackDataValid("/status"));
        assertTrue(TgBot.callbackDataValid("/ca"));
        assertTrue(TgBot.callbackDataValid("/cabackup"));
        assertTrue(TgBot.callbackDataValid("/careset"));
        assertTrue(TgBot.callbackDataValid("/stop 123456"));
        assertTrue(TgBot.callbackDataValid("/ca@NamaBot"));
        assertTrue(TgBot.callbackDataValid("/stop@Bot 123456"));
        assertFalse(TgBot.callbackDataValid("/hapus"));
        assertFalse(TgBot.callbackDataValid("status"));
        assertFalse(TgBot.callbackDataValid(""));
        assertFalse(TgBot.callbackDataValid(null));
    }

    @Test
    public void pisahkanPinEksplisitDanLegasi() {
        assertArrayEquals(new String[]{"", "123456"}, TgBot.pisahkanPin("123456"));
        assertArrayEquals(new String[]{"", "123456"}, TgBot.pisahkanPin("PIN:123456"));
        assertArrayEquals(new String[]{"", "123456"}, TgBot.pisahkanPin("pin: 123456"));
        assertArrayEquals(new String[]{"nama file.zip", "123456"},
                TgBot.pisahkanPin("nama file.zip PIN:123456"));
        assertArrayEquals(new String[]{"nama file.zip", "123456"},
                TgBot.pisahkanPin("nama file.zip 123456"));
        assertArrayEquals(new String[]{"YA", "123456"},
                TgBot.pisahkanPin("YA 123456"));
        assertArrayEquals(new String[]{"PIN:1111", "2222"}, TgBot.pisahkanPin("PIN:1111 PIN:2222"));
        assertArrayEquals(new String[]{"", ""}, TgBot.pisahkanPin(""));
        assertArrayEquals(new String[]{"", ""}, TgBot.pisahkanPin(null));
    }

    @Test
    public void pisahkanPinPemisahWhitespaceApapun() {
        assertArrayEquals(new String[]{"YA", "123456"},
                TgBot.pisahkanPin("YA\t123456"));
        assertArrayEquals(new String[]{"YA", "123456"},
                TgBot.pisahkanPin("YA\n123456"));
        assertArrayEquals(new String[]{"YA", "ab12"},
                TgBot.pisahkanPin("YA\tab12"));
        assertArrayEquals(new String[]{"YA", "123456"},
                TgBot.pisahkanPin("YA \t 123456"));
    }

    @Test
    public void pisahkanPinAlfanumerikDidukung() {
        // Kata tunggal huruf bukan PIN (argumen salah ketik tak boleh makan
        // lockout): PIN alnum kata tunggal wajib bentuk eksplisit PIN:.
        assertArrayEquals(new String[]{"ab12", ""}, TgBot.pisahkanPin("ab12"));
        assertArrayEquals(new String[]{"", "ab12"}, TgBot.pisahkanPin("PIN:ab12"));
        assertArrayEquals(new String[]{"webvault", ""}, TgBot.pisahkanPin("webvault"));
        assertArrayEquals(new String[]{"YA", "ab12"}, TgBot.pisahkanPin("YA ab12"));
        assertArrayEquals(new String[]{"restore", "PINku1"},
                TgBot.pisahkanPin("restore PIN:PINku1"));
    }

    @Test
    public void pisahkanPinSimbolDidukungKataHurufTetapArgumen() {
        assertArrayEquals(new String[]{"YA", "p@ss!9"}, TgBot.pisahkanPin("YA p@ss!9"));
        assertArrayEquals(new String[]{"YA", "ab12"}, TgBot.pisahkanPin("YA ab12"));
        assertArrayEquals(new String[]{"YA webvault", ""}, TgBot.pisahkanPin("YA webvault"));
        assertArrayEquals(new String[]{"foo bar", ""}, TgBot.pisahkanPin("foo bar"));
    }

    @Test
    public void pisahkanPinVersiBukanPin() {
        // Nomor versi di posisi akhir bukan PIN: salah ketik versi tak boleh
        // membakar lockout; PIN numerik murni tetap dimakan sebagai PIN.
        assertArrayEquals(new String[]{"1.32.0", ""}, TgBot.pisahkanPin("1.32.0"));
        assertArrayEquals(new String[]{"terbaru 1.32.0", ""},
                TgBot.pisahkanPin("terbaru 1.32.0"));
        assertArrayEquals(new String[]{"v1.32.0", ""}, TgBot.pisahkanPin("v1.32.0"));
        assertArrayEquals(new String[]{"1.32.0", "123456"},
                TgBot.pisahkanPin("1.32.0 123456"));
        assertArrayEquals(new String[]{"", "123456"}, TgBot.pisahkanPin("123456"));
        assertArrayEquals(new String[]{"1.32.x", ""}, TgBot.pisahkanPin("1.32.x"));
        assertArrayEquals(new String[]{"nama file.zip", ""},
                TgBot.pisahkanPin("nama file.zip"));
        assertArrayEquals(new String[]{"YA file.zip", ""},
                TgBot.pisahkanPin("YA file.zip"));
        assertArrayEquals(new String[]{"", "p@ss!9"}, TgBot.pisahkanPin("PIN:p@ss!9"));
    }

    @Test
    public void pisahkanPinTelanjangBukanPin() {
        // Token "PIN:" tanpa isi bukan PIN: salah ketik tak boleh dimakan
        // agar tak menambah hitungan lockout sia-sia.
        assertArrayEquals(new String[]{"foo PIN:", ""}, TgBot.pisahkanPin("foo PIN:"));
        assertArrayEquals(new String[]{"PIN:", ""}, TgBot.pisahkanPin("PIN:"));
    }

    @Test
    public void pisahkanPinKataBiasaTakDimakan() {
        assertArrayEquals(new String[]{"foo bar", ""}, TgBot.pisahkanPin("foo bar"));
        assertArrayEquals(new String[]{"foo", ""}, TgBot.pisahkanPin("foo"));
        assertArrayEquals(new String[]{"YA", ""}, TgBot.pisahkanPin("YA"));
        assertArrayEquals(new String[]{"YA webvault", ""}, TgBot.pisahkanPin("YA webvault"));
        assertArrayEquals(new String[]{"YA backup", ""}, TgBot.pisahkanPin("YA backup"));
        assertArrayEquals(new String[]{"YA", "ab12"}, TgBot.pisahkanPin("YA ab12"));
        assertArrayEquals(new String[]{"YA", "123456"}, TgBot.pisahkanPin("YA 123456"));
    }

    @Test
    public void pisahkanPinSpasiDidukungEksplisit() {
        assertArrayEquals(new String[]{"", "kunci saya 9"},
                TgBot.pisahkanPin("PIN:kunci saya 9"));
        assertArrayEquals(new String[]{"YA", "kunci saya 9"},
                TgBot.pisahkanPin("YA PIN:kunci saya 9"));
        assertArrayEquals(new String[]{"", "kunci saya"},
                TgBot.pisahkanPin("PIN:\"kunci saya\""));
        assertArrayEquals(new String[]{"1.32.0", "kunci saya"},
                TgBot.pisahkanPin("1.32.0 PIN:'kunci saya'"));
        assertArrayEquals(new String[]{"PIN:", ""}, TgBot.pisahkanPin("PIN:"));
    }

    @Test
    public void pisahkanPinKutipAwalanSisaKembali() {
        assertArrayEquals(new String[]{"extra", "kunci saya"},
                TgBot.pisahkanPin("PIN:\"kunci saya\" extra"));
        assertArrayEquals(new String[]{"YA extra", "kunci saya"},
                TgBot.pisahkanPin("YA PIN:'kunci saya' extra"));
        assertArrayEquals(new String[]{"", "kunci saya 9"},
                TgBot.pisahkanPin("PIN:kunci saya 9"));
    }

    @Test
    public void perintahBerbahayaTombol_caresetButuhPin() {
        assertTrue(TgBot.perintahBerbahayaTombol("/careset"));
        assertFalse(TgBot.perintahBerbahayaTombol("/ca"));
        assertFalse(TgBot.perintahBerbahayaTombol("/cabackup"));
    }

    @Test
    public void perintahBerbahaya_hanyaUbahKeadaan() {
        assertTrue(TgBot.perintahBerbahaya("/stop"));
        assertTrue(TgBot.perintahBerbahaya("/stop 123456"));
        assertTrue(TgBot.perintahBerbahaya("/restore YA 123456"));
        assertTrue(TgBot.perintahBerbahayaTombol("/stop"));
        // Perintah baca cukup auth chat agar tombol inline jalan saat PIN aktif.
        assertFalse(TgBot.perintahBerbahaya("/status"));
        assertFalse(TgBot.perintahBerbahaya("/status 123456"));
        assertFalse(TgBot.perintahBerbahayaTombol("/status"));
        assertFalse(TgBot.perintahBerbahaya("/log"));
        assertFalse(TgBot.perintahBerbahaya("/crashlog"));
        assertFalse(TgBot.perintahBerbahaya("/uptime"));
        assertFalse(TgBot.perintahBerbahaya("/alive"));
        assertFalse(TgBot.perintahBerbahaya("/help"));
        assertFalse(TgBot.perintahBerbahaya(null));
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

    @Test
    public void potongEkor_takMemenggalTengahBaris() {
        assertEquals("", TgBot.potongEkor(null, 10));
        assertEquals("abc", TgBot.potongEkor("abc", 10));
        // Potong di batas baris: sisa mulai setelah newline pertama di jendela.
        String panjang = "baris-satu-panjang\ndua\ntiga";
        String potong = TgBot.potongEkor(panjang, 8);
        assertTrue(potong.startsWith("..."));
        assertFalse(potong.contains("baris-satu-panjang"));
        // Tanpa newline di jendela: fallback potong persis.
        assertEquals("...cdef", TgBot.potongEkor("abcdef", 4));
    }

    @Test
    public void potongEkor_takBelahSurrogate() {
        String emoji = new String(Character.toChars(0x1F600));
        String teks = "ab" + emoji + "cd";
        String potong = TgBot.potongEkor(teks, 4);
        assertFalse(potong.contains("\uFFFD"));
        for (int i = 0; i < potong.length(); i++) {
            char c = potong.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < potong.length()
                        && Character.isLowSurrogate(potong.charAt(i + 1)));
            }
            if (Character.isLowSurrogate(c)) {
                assertTrue(i > 0 && Character.isHighSurrogate(potong.charAt(i - 1)));
            }
        }
    }

    @Test
    public void keyboardPerintah_memuatSemuaPerintah() {
        String json = TgBot.keyboardPerintah();
        assertTrue(json.startsWith("{\"inline_keyboard\":["));
        assertTrue(json.endsWith("]}"));
        for (String c : new String[]{"/status", "/log", "/uptime", "/alive", "/backup",
                "/restore", "/ca", "/cabackup", "/careset", "/crashlog", "/versi", "/update",
                "/webvault", "/start", "/stop", "/restart", "/help"}) {
            assertTrue("hilang: " + c, json.contains("\"" + c + "\""));
        }
    }

    @Test
    public void pisahkanPin_janganMakanKataVersi() {
        assertArrayEquals(new String[]{"terbaru", ""}, TgBot.pisahkanPin("terbaru"));
        assertArrayEquals(new String[]{"latest", ""}, TgBot.pisahkanPin("latest"));
        assertArrayEquals(new String[]{"TERBARU", ""}, TgBot.pisahkanPin("TERBARU"));
        assertArrayEquals(new String[]{"1.32.0", "123456"}, TgBot.pisahkanPin("1.32.0 123456"));
        assertArrayEquals(new String[]{"terbaru", "123456"}, TgBot.pisahkanPin("terbaru 123456"));
        assertArrayEquals(new String[]{"", "123456"}, TgBot.pisahkanPin("123456"));
    }

    @Test
    public void lolosJson_amankanKutipDanKontrol() {
        assertEquals("", TgBot.lolosJson(null));
        assertEquals("a\\\"b\\\\c", TgBot.lolosJson("a\"b\\c"));
        assertEquals("x\\ny", TgBot.lolosJson("x\ny"));
        // Escape \\uXXXX wajib digit ASCII walau locale berdigit non-Latin.
        java.util.Locale semula = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("ar", "EG"));
            assertEquals("\\u0001", TgBot.lolosJson("\u0001"));
        } finally {
            java.util.Locale.setDefault(semula);
        }
        String json = TgBot.menuPayload();
        assertTrue(json.startsWith("{\"commands\":["));
        assertTrue(json.endsWith("]}"));
    }

    @Test
    public void lolosJson_amankanUnicodeKhususDanSurrogate() {
        assertEquals("\\u2028", TgBot.lolosJson("\u2028"));
        assertEquals("\\u2029", TgBot.lolosJson("\u2029"));
        // Surrogate yatim di-escape, pasangan valid (emoji) dibiarkan utuh.
        assertEquals("\\ud800", TgBot.lolosJson("\ud800"));
        assertEquals("\\udc00", TgBot.lolosJson("\udc00"));
        assertEquals("\ud83d\ude00", TgBot.lolosJson("\ud83d\ude00"));
    }

    @Test
    public void pisahkanPin_dukungMultiBaris() {
        assertArrayEquals(new String[]{"", "baris1\nbaris2"},
                TgBot.pisahkanPin("PIN:baris1\nbaris2"));
    }

    @Test
    public void pesanPinWajibHapus_crashlogIkutDihapus() {
        // /log + /crashlog ber-PIN yang lolos wajib dihapus dari riwayat
        // (bawa PIN asli + isi sensitif), walau keduanya bukan
        // perintahBerbahaya (tombol inline tetap jalan, ditolak halus).
        assertTrue(TgBot.pesanPinWajibHapus("/crashlog 123456"));
        assertTrue(TgBot.pesanPinWajibHapus("/log 123456"));
        assertTrue(TgBot.pesanPinWajibHapus("/status 123456"));
        assertTrue(TgBot.pesanPinWajibHapus("/stop 123456"));
        assertTrue(TgBot.pesanPinWajibHapus("/careset 123456"));
        assertFalse(TgBot.pesanPinWajibHapus("/help"));
        assertFalse(TgBot.pesanPinWajibHapus(null));
        assertFalse(TgBot.pesanPinWajibHapus(""));
        assertFalse(TgBot.perintahBerbahaya("/crashlog"));
    }

    @Test
    public void parseUsernameBot_kupasUsernameGetMe() {
        assertEquals("MyBot", TgBot.parseUsernameBot(
                "{\"ok\":true,\"result\":{\"username\":\"MyBot\"}}"));
        assertEquals("MyBot", TgBot.parseUsernameBot(
                "{\"ok\":true,\"result\":{\"username\":\"@MyBot\"}}"));
        assertEquals("", TgBot.parseUsernameBot("{\"ok\":false}"));
        assertEquals("", TgBot.parseUsernameBot("{\"ok\":true}"));
        assertEquals("", TgBot.parseUsernameBot(""));
        assertEquals("", TgBot.parseUsernameBot(null));
        assertEquals("", TgBot.parseUsernameBot("bukan json"));
    }
}
