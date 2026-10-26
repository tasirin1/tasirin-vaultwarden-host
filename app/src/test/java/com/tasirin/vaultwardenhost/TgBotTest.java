package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TgBotTest {

    @Test
    public void peringatkanHapusPinAbaikanContextNull() {

        TgBot.peringatkanBilaTakBisaHapus(null, true, 123456789L);
        TgBot.peringatkanBilaTakBisaHapus(null, false, -1001234567890L);
    }

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
    public void durationText_formatStabil() {
        assertEquals("0 detik", TgBot.durationText(0));
        assertEquals("5 detik", TgBot.durationText(5000));
        assertEquals("3 menit 4 detik", TgBot.durationText((3 * 60 + 4) * 1000L));
        assertEquals("1 jam 2 menit", TgBot.durationText((3600 + 120) * 1000L));
        assertEquals("25 jam 0 menit", TgBot.durationText(25L * 3600 * 1000));
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
    public void keyboardMenu_utamaMemuatKategori() {
        String json = TgBot.keyboardMenu(TgBot.MENU_UTAMA);
        assertTrue(json.startsWith("{\"inline_keyboard\":["));
        assertTrue(json.endsWith("]}"));
        for (String c : new String[]{"m:status", "m:kontrol", "m:backup", "m:update",
                "m:sertifikat", "m:info"}) {
            assertTrue("hilang: " + c, json.contains("\"" + c + "\""));
        }

        assertEquals(json, TgBot.keyboardMenu("tidak-ada"));
        assertTrue(TgBot.panelMenuValid(TgBot.MENU_KONTROL));
        assertFalse(TgBot.panelMenuValid("batal"));
        assertFalse(TgBot.panelMenuValid(null));
        assertEquals("{\"inline_keyboard\":[["
                        + "{\"text\":\"A\",\"callback_data\":\"m:utama\"},"
                        + "{\"text\":\"B\",\"callback_data\":\"/stop\"}]]}",
                TgBot.rakitKeyboard(new String[][]{
                        {"A", "m:utama"}, {"B", "/stop"},
                }));
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
        assertTrue(TgBot.callbackDataValid("m:utama"));
        assertTrue(TgBot.callbackDataValid("m:sertifikat"));
        assertFalse(TgBot.callbackDataValid("m:batal"));
        assertFalse(TgBot.callbackDataValid("m:hapus"));
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

        assertArrayEquals(new String[]{"ab12", ""}, TgBot.pisahkanPin("ab12"));
        assertArrayEquals(new String[]{"", "ab12"}, TgBot.pisahkanPin("PIN:ab12"));
        assertArrayEquals(new String[]{"webvault", ""}, TgBot.pisahkanPin("webvault"));
        assertArrayEquals(new String[]{"YA", "ab12"}, TgBot.pisahkanPin("YA ab12"));
        assertArrayEquals(new String[]{"restore", "PINku1"},
                TgBot.pisahkanPin("restore PIN:PINku1"));
    }

    @Test
    public void pinBerisiMarkerAtauSpasiDipakaiLewatPenandaInternal() {

        String pinAneh = "kunci PIN:99 ";
        assertEquals(pinAneh, TgBot.pinDariPenanda(TgBot.pinKePenanda(pinAneh)));
        assertArrayEquals(new String[]{"/stop", pinAneh},
                TgBot.pisahkanPin("/stop " + TgBot.pinKePenanda(pinAneh)));
        assertEquals("", TgBot.pinDariPenanda("tanpa penanda"));
        assertEquals("", TgBot.pinDariPenanda(TgBot.pinKePenanda("")));

        assertArrayEquals(new String[]{"", "kunci PIN:99"},
                TgBot.pisahkanPin("PIN:\"kunci PIN:99\""));
        assertArrayEquals(new String[]{"YA", "kunci PIN:99"},
                TgBot.pisahkanPin("YA PIN:\"kunci PIN:99\""));
        assertEquals("kunci PIN:99", TgBot.pinDariBalasan("PIN:\"kunci PIN:99\""));
    }

    @Test
    public void pisahkanPinSimbolDidukungKataHurufTetapArgumen() {
        assertArrayEquals(new String[]{"YA", "p@ss!9"}, TgBot.pisahkanPin("YA p@ss!9"));
        assertArrayEquals(new String[]{"YA", "ab12"}, TgBot.pisahkanPin("YA ab12"));
        assertArrayEquals(new String[]{"YA webvault", ""}, TgBot.pisahkanPin("YA webvault"));
        assertArrayEquals(new String[]{"foo bar", ""}, TgBot.pisahkanPin("foo bar"));
    }

    @Test
    public void pinDariBalasanBedakanPinDanObrolan() {

        assertEquals("123456", TgBot.pinDariBalasan("123456"));
        assertEquals("123456", TgBot.pinDariBalasan("  123456 "));
        assertEquals("ab12", TgBot.pinDariBalasan("ab12"));

        assertEquals("123456", TgBot.pinDariBalasan("PIN:123456"));
        assertEquals("hunter2", TgBot.pinDariBalasan("pin: hunter2"));
        assertEquals("kunci saya", TgBot.pinDariBalasan("PIN:\"kunci saya\""));
        assertEquals("p@ss", TgBot.pinDariBalasan("PIN:p@ss"));

        assertNull(TgBot.pinDariBalasan("halo"));
        assertNull(TgBot.pinDariBalasan("tolong bantu"));
        assertNull(TgBot.pinDariBalasan("webvault"));
        assertNull(TgBot.pinDariBalasan("p@ss"));
        assertNull(TgBot.pinDariBalasan("123"));
        assertNull(TgBot.pinDariBalasan("PIN:"));
        assertNull(TgBot.pinDariBalasan(""));
        assertNull(TgBot.pinDariBalasan(null));
        assertNull(TgBot.pinDariBalasan("1234567890".repeat(13)));
    }

    @Test
    public void pisahkanPinVersiBukanPin() {

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
    public void perintahBerbahaya_caresetButuhPin() {
        assertTrue(TgBot.perintahBerbahaya("/careset"));
        assertFalse(TgBot.perintahBerbahaya("/ca"));
        assertFalse(TgBot.perintahBerbahaya("/cabackup"));
    }

    @Test
    public void perintahBerbahaya_hanyaUbahKeadaan() {
        assertTrue(TgBot.perintahBerbahaya("/stop"));
        assertTrue(TgBot.perintahBerbahaya("/stop 123456"));
        assertTrue(TgBot.perintahBerbahaya("/restore YA 123456"));

        assertFalse(TgBot.perintahBerbahaya("/status"));
        assertFalse(TgBot.perintahBerbahaya("/status 123456"));
        assertFalse(TgBot.perintahBerbahaya("/log"));
        assertFalse(TgBot.perintahBerbahaya("/crashlog"));
        assertFalse(TgBot.perintahBerbahaya("/ca"));
        assertFalse(TgBot.perintahBerbahaya("/cabackup"));
        assertFalse(TgBot.perintahBerbahaya("/uptime"));
        assertFalse(TgBot.perintahBerbahaya("/alive"));
        assertFalse(TgBot.perintahBerbahaya("/help"));
        assertFalse(TgBot.perintahBerbahaya(null));
    }

    @Test
    public void perintahPerluPin_termasukPembacaSensitif() {
        assertTrue(TgBot.perintahPerluPin("/stop 123456"));
        assertTrue(TgBot.perintahPerluPin("/careset"));
        assertTrue(TgBot.perintahPerluPin("/status"));
        assertTrue(TgBot.perintahPerluPin("/log 123456"));
        assertTrue(TgBot.perintahPerluPin("/crashlog"));
        assertTrue(TgBot.perintahPerluPin("/ca"));
        assertTrue(TgBot.perintahPerluPin("/cabackup"));
        assertFalse(TgBot.perintahPerluPin("/uptime"));
        assertFalse(TgBot.perintahPerluPin("/alive"));
        assertFalse(TgBot.perintahPerluPin("/versi"));
        assertFalse(TgBot.perintahPerluPin("/help"));
        assertFalse(TgBot.perintahPerluPin(null));
    }

    @Test
    public void pendingMasihBerlaku_batasLimaMenit() {
        long dibuat = 1_000_000L;
        assertTrue(TgBot.pendingMasihBerlaku(dibuat, dibuat));
        assertTrue(TgBot.pendingMasihBerlaku(dibuat, dibuat + TgBot.PENDING_AKSI_MS));
        assertFalse(TgBot.pendingMasihBerlaku(dibuat,
                dibuat + TgBot.PENDING_AKSI_MS + 1));
        assertFalse(TgBot.pendingMasihBerlaku(dibuat, dibuat - 1));
        assertFalse(TgBot.pendingMasihBerlaku(0, dibuat));
    }

    @Test
    public void restoreConfirm_tolakKosongDanAsing() {
        assertFalse(TgBot.isRestoreConfirm(null));
        assertFalse(TgBot.isRestoreConfirm(""));
        assertFalse(TgBot.isRestoreConfirm("tidak"));
        assertFalse(TgBot.isRestoreConfirm("backup"));
        assertFalse(TgBot.isRestoreConfirm("YA sekarang"));

        assertFalse(TgBot.isRestoreConfirm("ok"));
        assertFalse(TgBot.isRestoreConfirm("Y"));
        assertFalse(TgBot.isRestoreConfirm("lanjut"));
    }

    @Test
    public void potongEkor_takMemenggalTengahBaris() {
        assertEquals("", TgBot.potongEkor(null, 10));
        assertEquals("abc", TgBot.potongEkor("abc", 10));

        String panjang = "baris-satu-panjang\ndua\ntiga";
        String potong = TgBot.potongEkor(panjang, 8);
        assertTrue(potong.startsWith("..."));
        assertFalse(potong.contains("baris-satu-panjang"));

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
    public void keyboardMenu_panelMemuatPerintahDanKembali() {
        assertTrue(TgBot.keyboardMenu(TgBot.MENU_STATUS)
                .contains("\"/status\""));
        assertTrue(TgBot.keyboardMenu(TgBot.MENU_STATUS)
                .contains("\"m:utama\""));
        assertTrue(TgBot.keyboardMenu(TgBot.MENU_KONTROL)
                .contains("\"/stop\""));
        assertTrue(TgBot.keyboardMenu(TgBot.MENU_BACKUP)
                .contains("\"/backup\""));
        assertTrue(TgBot.keyboardMenu(TgBot.MENU_UPDATE)
                .contains("\"/webvault\""));
        assertTrue(TgBot.keyboardMenu(TgBot.MENU_SERTIFIKAT)
                .contains("\"/careset\""));
        assertTrue(TgBot.keyboardMenu(TgBot.MENU_INFO)
                .contains("\"m:utama\""));
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

        assertTrue(TgBot.pesanPinWajibHapus("/crashlog 123456"));
        assertTrue(TgBot.pesanPinWajibHapus("/log 123456"));
        assertTrue(TgBot.pesanPinWajibHapus("/status 123456"));
        assertTrue(TgBot.pesanPinWajibHapus("/ca 123456"));
        assertTrue(TgBot.pesanPinWajibHapus("/cabackup 123456"));
        assertTrue(TgBot.pesanPinWajibHapus("/stop 123456"));
        assertTrue(TgBot.pesanPinWajibHapus("/careset 123456"));
        assertFalse(TgBot.pesanPinWajibHapus("/help"));
        assertFalse(TgBot.pesanPinWajibHapus(null));
        assertFalse(TgBot.pesanPinWajibHapus(""));
        assertFalse(TgBot.perintahBerbahaya("/crashlog"));
    }

    @Test
    public void wajibHapusPesanPin_pinSalahIkutDihapus() {
        assertTrue(TgBot.wajibHapusPesanPin(false, false, true, false, false, "123457"));
        assertTrue(TgBot.wajibHapusPesanPin(false, false, true, false, true, "123456"));
        assertFalse(TgBot.wajibHapusPesanPin(false, false, true, true, false, "halo"));
        assertTrue(TgBot.wajibHapusPesanPin(false, false, false, false, false, "/stop 123457"));
        assertFalse(TgBot.wajibHapusPesanPin(false, false, false, false, false, "/stop"));
        assertTrue(TgBot.wajibHapusPesanPin(false, false, false, false, true, "/stop"));
        assertTrue(TgBot.wajibHapusPesanPin(true, false, false, false, false, "apa pun"));
        assertFalse(TgBot.wajibHapusPesanPin(false, false, false, false, false, "/status"));
    }

    @Test
    public void wajibPinSaatPinMatiUntukChatAnggapGrup() {
        assertTrue(TgBot.wajibPinSaatPinMati(true, false));
        assertTrue(TgBot.wajibPinSaatPinMati(false, true));
        assertTrue(TgBot.wajibPinSaatPinMati(true, true));
        assertFalse(TgBot.wajibPinSaatPinMati(false, false));
    }

    @Test
    public void pinTebakan_grupHanyaKenaliTebakanSah() {

        assertEquals("123456", TgBot.pinTebakan("/stop 123456"));
        assertEquals("123456", TgBot.pinTebakan("/status 123456"));
        assertEquals("ab12", TgBot.pinTebakan("/stop PIN:ab12"));
        assertEquals("kunci saya", TgBot.pinTebakan("/stop PIN:\"kunci saya\""));
        assertNull(TgBot.pinTebakan("/stop"));
        assertNull(TgBot.pinTebakan("/stop 123"));
        assertNull(TgBot.pinTebakan("/help 123456"));
        assertNull(TgBot.pinTebakan("/update 1.32.0"));
        assertNull(TgBot.pinTebakan("/update terbaru"));
        assertNull(TgBot.pinTebakan(null));
        assertNull(TgBot.pinTebakan(""));
        assertNull(TgBot.pinTebakanArgumen(null));
        assertEquals("123456", TgBot.pinTebakanArgumen("123456"));
        assertNull(TgBot.pinTebakanArgumen("1.32.0"));
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
    @Test
    public void rapikanSpasiManual() {
        assertEquals("a b c", TgBot.rapikanSpasi("a   b\t\nc"));
        assertEquals("", TgBot.rapikanSpasi(null));
        assertEquals("", TgBot.namaPerintahUntukBot("  /start@BotLain  ", "BotKu"));
        assertEquals("start", TgBot.namaPerintahUntukBot("  /start  ", "BotKu"));
    }

    @Test
    public void idBotDariToken_kenaliPemilikToken() {
        assertEquals("123456789", TgBot.idBotDariToken("123456789:AAF-abc_xyz"));
        assertEquals("123456789", TgBot.idBotDariToken("  123456789:AAF  "));

        assertEquals("", TgBot.idBotDariToken(null));
        assertEquals("", TgBot.idBotDariToken(""));
        assertEquals("", TgBot.idBotDariToken("tanpa-titik-dua"));
        assertEquals("", TgBot.idBotDariToken(":AAF"));
        assertEquals("", TgBot.idBotDariToken("abc:AAF"));
        assertEquals("", TgBot.idBotDariToken("123456789012345678901:AAF"));
    }

    @Test
    public void responsOk_toleranSpasi() {
        assertTrue(TgBot.responsOk("{\"ok\":true,\"result\":{}}"));
        assertTrue(TgBot.responsOk("{\"ok\": true,\"result\":{}}"));
        assertTrue(TgBot.responsOk("{ \"ok\" : true }"));
        assertFalse(TgBot.responsOk("{\"ok\":false}"));
        assertFalse(TgBot.responsOk(null));
        assertFalse(TgBot.responsOk(""));
    }

    @Test
    public void kecilkanRentang_lowerAsciiJepitBatas() {
        assertEquals("start", TgBot.kecilkanRentang("/START x", 1, 6));
        assertEquals("", TgBot.kecilkanRentang("/START", 7, 3));
        assertEquals("", TgBot.kecilkanRentang(null, 0, 5));
        assertEquals("", TgBot.kecilkanRentang("ab", -9, 0));
        assertEquals("ab", TgBot.kecilkanRentang("AB", -9, 99));
    }

    @Test
    public void bolehCobaGetMe_throttleSatuJamPerToken() {
        assertTrue(TgBot.bolehCobaGetMe("tokA", "", 0, 1000));
        assertTrue(TgBot.bolehCobaGetMe("tokB", "tokA", 1000, 2000));
        assertFalse(TgBot.bolehCobaGetMe("tokA", "tokA", 1000, 2000));
        assertTrue(TgBot.bolehCobaGetMe("tokA", "tokA", 1000,
                1000 + TgBot.GETME_ULANG_MS));
        assertTrue(TgBot.bolehCobaGetMe("tokA", "tokA", 5000, 1000));
        assertFalse(TgBot.bolehCobaGetMe("", "tokA", 0, 1000));
        assertFalse(TgBot.bolehCobaGetMe(null, "tokA", 0, 1000));
    }

    @Test
    public void ringkasGalatTelegram_kodeDanDeskripsi() {
        assertEquals("HTTP 401: Unauthorized",
                TgBot.ringkasGalatTelegram(
                        "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}",
                        401));
        assertEquals("HTTP 409: Conflict: terminated by other getUpdates request",
                TgBot.ringkasGalatTelegram(
                        "{\"ok\": false, \"error_code\": 409, "
                                + "\"description\": \"Conflict: terminated by other getUpdates request\"}",
                        409));

        assertEquals("HTTP 500", TgBot.ringkasGalatTelegram(null, 500));
        assertEquals("HTTP 502", TgBot.ringkasGalatTelegram("<html>bad gateway</html>", 502));
        assertEquals("tanpa keterangan", TgBot.ringkasGalatTelegram(null, 0));

        StringBuilder isiPanjang = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            isiPanjang.append('x');
        }
        String body = "{\"error_code\":429,\"description\":\"" + isiPanjang + "\"}";
        assertEquals("HTTP 429: " + isiPanjang.substring(0, 160),
                TgBot.ringkasGalatTelegram(body, 429));
    }

    @Test
    public void wallBolehMaju_tolakJam1970DanLompatanRaksasa() {
        long sane = 1_700_000_000_000L;
        assertTrue(TgBot.wallBolehMaju(0, sane));
        assertTrue(TgBot.wallBolehMaju(sane, sane + 3_600_000L));
        assertTrue(TgBot.wallBolehMaju(sane,
                sane + TgBot.BATAS_LOMPATAN_JAM_MS));
        assertFalse(TgBot.wallBolehMaju(sane,
                sane + TgBot.BATAS_LOMPATAN_JAM_MS + 1));
        assertFalse(TgBot.wallBolehMaju(sane, sane));
        assertFalse(TgBot.wallBolehMaju(sane, sane - 1_000L));
        assertFalse(TgBot.wallBolehMaju(0, 5_000L));
    }

    @Test
    public void wakelockTugasBeratCukupUntukUnduhanLambat() {

        long menit = TgBot.WAKELOCK_TUGAS_BERAT_MS / 60_000L;
        assertTrue("wakelock tugas berat minimal 15 menit, kini " + menit,
                menit >= 15);
        long mbDetik = 35L * 1024L * 1024L / (30L * 1024L);
        assertTrue("harus menutup 35 MB @30 KB/s (" + mbDetik + " dtk)",
                TgBot.WAKELOCK_TUGAS_BERAT_MS / 1000L > mbDetik);
    }

    @Test
    public void pesanAliveMembedakanSehatDanTidak() {
        assertEquals("Server sehat (HTTPS 200 /alive).", TgBot.pesanAlive(true));
        assertEquals("Server TIDAK merespon /alive!", TgBot.pesanAlive(false));
    }
}
