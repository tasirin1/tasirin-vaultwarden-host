package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test kripto PIN murni (tanpa Android runtime). */
public class PinCryptoTest {

    @Test
    public void hashLaluVerifikasi() {
        String h = PinCrypto.hash("123456");
        assertTrue(PinCrypto.isNewFormat(h));
        assertTrue(PinCrypto.verify(h, "123456"));
        assertFalse(PinCrypto.verify(h, "123457"));
        assertFalse(PinCrypto.verify(h, ""));
    }

    @Test
    public void saltAcakTiapHash() {
        assertFalse(PinCrypto.hash("1234").equals(PinCrypto.hash("1234")));
    }

    @Test
    public void iterasiRendahDitolak() {
        // Hash beriterasi sangat rendah (hasil utak-atik) wajib ditolak
        // fail-closed walau formatnya valid.
        String palsu = "PBKDF2$100$"
                + "00112233445566778899aabbccddeeff"
                + "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
        assertFalse(PinCrypto.verify(palsu, "1234"));
        assertFalse(PinCrypto.verify(palsu, ""));
    }

    @Test
    public void iterasiRaksasaDitolak() {
        // Hash utak-atik beriterasi 1 jt memaksa PBKDF2 ~8x (stall STB):
        // wajib ditolak sebelum derive, hash 120k bawaan tetap lolos.
        String raksasa = "PBKDF2$1000000$"
                + "00112233445566778899aabbccddeeff"
                + "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
        assertFalse(PinCrypto.verify(raksasa, "1234"));
        assertTrue(PinCrypto.verify(PinCrypto.hash("1234"), "1234"));
    }

    @Test
    public void pinKosongSelaluDitolak() {
        assertFalse(PinCrypto.verify(PinCrypto.hash("1234"), ""));
        assertFalse(PinCrypto.verify(PinCrypto.sha256("1234"), ""));
        assertTrue(PinCrypto.perluMigrasi(PinCrypto.sha256("1234")));
        assertFalse(PinCrypto.perluMigrasi(PinCrypto.hash("1234")));
    }

    @Test
    public void hashLamaTetapDikenali() {
        String lama = PinCrypto.sha256("9999");
        assertFalse(PinCrypto.isNewFormat(lama));
        assertTrue(PinCrypto.verify(lama, "9999"));
        assertFalse(PinCrypto.verify(lama, "0000"));
    }

    @Test
    public void kunciLimaGagalBeruntun() {
        // Wall-clock agar reboot tak mereset lockout.
        long sekarang = 1_700_000_000_000L;
        assertTrue(PinCrypto.sisaKunciMs(4, 0, sekarang) == 0);
        long sampai = PinCrypto.kunciBerikutnyaMs(5, sekarang);
        assertTrue(sampai == sekarang + PinCrypto.KUNCI_MS);
        assertTrue(PinCrypto.sisaKunciMs(5, sampai, sekarang) == PinCrypto.KUNCI_MS);
        assertTrue(PinCrypto.sisaKunciMs(5, sampai, sampai) == 0);
        assertTrue(PinCrypto.sisaKunciMs(5, sampai, sampai + 1) == 0);
    }

    @Test
    public void kunciTanpaStempelFailClosed() {
        // Hitungan gagal tanpa stempel (prefs korup/kunci dihapus) tak boleh
        // membuka lockout: fail-closed dengan kunci penuh.
        long sekarang = 1_700_000_000_000L;
        assertTrue(PinCrypto.sisaKunciMs(5, 0, sekarang) == PinCrypto.KUNCI_MS);
        assertTrue(PinCrypto.sisaKunciMs(6, -1, sekarang) == PinCrypto.KUNCI_MS);
        assertTrue(PinCrypto.sisaKunciMs(4, 0, sekarang) == 0);
    }

    @Test
    public void kunciElapsedLamaDianggapKedaluwarsa() {
        // Format lama era elapsedRealtime tak boleh mengunci permanen.
        assertTrue(PinCrypto.sisaKunciMs(5, 1_300_000L, 1_700_000_000_000L) == 0);
    }

    @Test
    public void kunciWallBertahanSeolahReboot() {
        // Simulasi reboot: jam monoton reset tapi wall-clock jalan terus.
        long terkunci = 1_700_000_000_000L + PinCrypto.KUNCI_MS;
        assertTrue(PinCrypto.sisaKunciMs(5, terkunci, 1_700_000_000_000L + 60_000L) > 0);
    }

    @Test
    public void kunciWallBasiDianggapKedaluwarsa() {
        long wallBasi = 1_700_000_000_000L;
        long elapsed = 1_000_000L;
        assertTrue(PinCrypto.sisaKunciMs(5, wallBasi, elapsed) == PinCrypto.KUNCI_MS);
    }

    @Test
    public void jamMundurTetapKunci() {
        long sampai = 1_700_000_000_000L + PinCrypto.KUNCI_MS;
        assertTrue(PinCrypto.sisaKunciMs(5, sampai, 1_000_000L) == PinCrypto.KUNCI_MS);
    }

    @Test
    public void inputRusakDitolak() {
        assertFalse(PinCrypto.verify(null, "1"));
        assertFalse(PinCrypto.verify("", "1"));
        assertFalse(PinCrypto.verify(PinCrypto.hash("1"), null));
        assertFalse(PinCrypto.verify("PBKDF2$xxx", "1"));
        assertFalse(PinCrypto.verify("PBKDF2$0$zz$zz", "1"));
    }

    @Test
    public void kunciElapsedTahanResetJam() {
        // Jam di-reset ke 1970 setelah 5 gagal: wall lolos tapi elapsed tetap kunci.
        long elapsed = 500_000L;
        long sampai = PinCrypto.kunciElapsedBerikutnyaMs(5, elapsed);
        assertTrue(sampai == elapsed + PinCrypto.KUNCI_MS);
        assertTrue(PinCrypto.sisaKunciElapsed(sampai, elapsed + 60_000L) > 0);
        assertTrue(PinCrypto.sisaKunciElapsed(sampai, sampai) == 0);
        assertTrue(PinCrypto.sisaKunciElapsed(sampai, sampai + 1) == 0);
        assertTrue(PinCrypto.kunciElapsedBerikutnyaMs(4, elapsed) == 0);
    }

    @Test
    public void kunciElapsedBasiSehabisRebootTakMengunci() {
        // Reboot me-reset elapsed: selisih meledak di atas KUNCI_MS -> dianggap basi.
        assertTrue(PinCrypto.sisaKunciElapsed(500_000L, 10_000L) == 0);
        assertTrue(PinCrypto.sisaKunciElapsed(0L, 10_000L) == 0);
    }

    @Test
    public void hashLemahDimintaUpgrade() {
        assertTrue(PinCrypto.perluUpgradeHash(PinCrypto.sha256("1234")));
        String lemah = "PBKDF2$10000$"
                + "00112233445566778899aabbccddeeff"
                + "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
        assertTrue(PinCrypto.perluUpgradeHash(lemah));
        assertFalse(PinCrypto.perluUpgradeHash(PinCrypto.hash("1234")));
        assertFalse(PinCrypto.perluUpgradeHash(null));
        assertFalse(PinCrypto.perluUpgradeHash(""));
    }
}
