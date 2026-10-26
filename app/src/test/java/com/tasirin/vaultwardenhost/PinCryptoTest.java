package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
    public void pbkdf2ManualSamadenganFactory() throws Exception {

        byte[] salt = new byte[16];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) (i * 7 + 3);
        }
        String[] sampel = {"1234", "123456", "pinAb12", "4ngk4-k3r4s!"};
        for (String pin : sampel) {
            byte[] manual = PinCrypto.pbkdf2Manual(pin, salt, 10000);
            javax.crypto.spec.PBEKeySpec spec =
                    new javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 10000, 256);
            byte[] pabrik = javax.crypto.SecretKeyFactory
                    .getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            org.junit.Assert.assertArrayEquals(pabrik, manual);
        }
    }

    @Test
    public void standarLegasiDibawahModernDiatasBatas() {

        int legasi = PinCrypto.iterasiStandar(false);
        int modern = PinCrypto.iterasiStandar(true);
        org.junit.Assert.assertTrue(legasi >= 10000);
        org.junit.Assert.assertTrue(legasi < modern);
        org.junit.Assert.assertTrue(modern <= 120000);
    }

    @Test
    public void hashLegasiLolosVerifikasiAntarPerangkat() {

        byte[] salt = new byte[16];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) (i * 3 + 1);
        }
        int iter = PinCrypto.iterasiStandar(false);
        byte[] dk = PinCrypto.pbkdf2Manual("1234", salt, iter);
        String simpan = "PBKDF2$" + iter + "$"
                + PinCrypto.hex(salt) + "$" + PinCrypto.hex(dk);
        assertTrue(PinCrypto.verify(simpan, "1234"));
        assertFalse(PinCrypto.verify(simpan, "4321"));
    }

    @Test
    public void pbkdf2Manual_cocokVektorReferensi() {
        byte[] salt = "salt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.junit.Assert.assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
                PinCrypto.hex(PinCrypto.pbkdf2Manual("password", salt, 1)));
        org.junit.Assert.assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
                PinCrypto.hex(PinCrypto.pbkdf2Manual("password", salt, 4096)));
    }

    @Test
    public void pengkodeanNonAscii_keduaJalurSesuaiReferensi() {
        byte[] salt = new byte[16];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) i;
        }
        org.junit.Assert.assertEquals("74991ed4e4f7ecb60f08848ae643309dd95eeccd8570b907d95aadba1cb21db2",
                PinCrypto.hex(PinCrypto.pbkdf2Manual("päss12", salt, 30000)));
        org.junit.Assert.assertEquals("1ff577891850574904fe8a7d08bb3429a313651ea654b386457a995864f54698",
                PinCrypto.hex(PinCrypto.pbkdf2ManualByte(
                        PinCrypto.byteRendah("päss12"), salt, 30000)));
        org.junit.Assert.assertArrayEquals(
                new byte[]{112, -28, 115, 115, 49, 50},
                PinCrypto.byteRendah("päss12"));
    }

    @Test
    public void hashNonAsciiDariJalurManual_tetapLolosVerifikasi() {
        String simpan = "PBKDF2$30000$000102030405060708090a0b0c0d0e0f$"
                + "74991ed4e4f7ecb60f08848ae643309dd95eeccd8570b907d95aadba1cb21db2";
        assertTrue(PinCrypto.verify(simpan, "päss12"));
        assertFalse(PinCrypto.verify(simpan, "päss13"));
        assertFalse(PinCrypto.verify(simpan, "pass12"));
    }

    @Test
    public void pbkdf2ManualIterasiBesarSamadenganFactory() throws Exception {

        byte[] salt = new byte[16];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) (i * 11 + 5);
        }
        byte[] manual = PinCrypto.pbkdf2Manual("katasandi", salt, 100000);
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                "katasandi".toCharArray(), salt, 100000, 256);
        byte[] pabrik = javax.crypto.SecretKeyFactory
                .getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded();
        org.junit.Assert.assertArrayEquals(pabrik, manual);
    }

    @Test
    public void saltAcakTiapHash() {
        assertFalse(PinCrypto.hash("1234").equals(PinCrypto.hash("1234")));
    }

    @Test
    public void iterasiRendahDitolak() {

        String palsu = "PBKDF2$100$"
                + "00112233445566778899aabbccddeeff"
                + "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
        assertFalse(PinCrypto.verify(palsu, "1234"));
        assertFalse(PinCrypto.verify(palsu, ""));
    }

    @Test
    public void iterasiRaksasaDitolak() {

        String raksasa = "PBKDF2$1000000$"
                + "00112233445566778899aabbccddeeff"
                + "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
        assertFalse(PinCrypto.verify(raksasa, "1234"));
        assertTrue(PinCrypto.verify(PinCrypto.hash("1234"), "1234"));
    }

    @Test
    public void heksRaksasaDitolak() {

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            sb.append('a');
        }
        String gede = sb.toString();
        assertFalse(PinCrypto.verify("PBKDF2$120000$" + gede + "$" + gede, "1234"));
        assertFalse(PinCrypto.verify("PBKDF2W$120000$" + gede + "$" + gede, "1234"));
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
    public void bungkusDiniKuatkanHashLegasi() {
        String lama = PinCrypto.sha256("9999");
        String bungkus = PinCrypto.bungkusLegasi(lama);
        assertTrue(bungkus.startsWith("PBKDF2W$30000$"));

        assertTrue(PinCrypto.verify(bungkus, "9999"));
        assertFalse(PinCrypto.verify(bungkus, "0000"));
        assertFalse(PinCrypto.verify(bungkus, ""));

        assertTrue(PinCrypto.perluUpgradeHash(bungkus));
        assertFalse(PinCrypto.perluMigrasi(bungkus));

        assertTrue(PinCrypto.bungkusLegasi(null) == null);
        assertTrue(PinCrypto.bungkusLegasi("") == null);
        assertTrue(PinCrypto.bungkusLegasi("xyz") == null);
        assertTrue(PinCrypto.bungkusLegasi(PinCrypto.hash("9999")) == null);
    }

    @Test
    public void bungkusDiniTolakUtakAtik() {
        String bungkus = PinCrypto.bungkusLegasi(PinCrypto.sha256("9999"));

        assertFalse(PinCrypto.verify(
                bungkus.replace("PBKDF2W$30000$", "PBKDF2W$1000000$"), "9999"));
        assertFalse(PinCrypto.verify(
                bungkus.replace("PBKDF2W$30000$", "PBKDF2W$100$"), "9999"));
        assertFalse(PinCrypto.verify("PBKDF2W$xxx", "9999"));
    }

    @Test
    public void normalisasiIterasiLama() {

        String kini = PinCrypto.hash("1234");
        assertTrue(kini.startsWith("PBKDF2$30000$"));
        assertFalse(PinCrypto.perluUpgradeHash(kini));

        String berat = kini.replace("PBKDF2$30000$", "PBKDF2$120000$");
        assertTrue(PinCrypto.perluUpgradeHash(berat));

        String lemah = kini.replace("PBKDF2$30000$", "PBKDF2$10000$");
        assertTrue(PinCrypto.perluUpgradeHash(lemah));
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

        long sekarang = 1_700_000_000_000L;
        assertTrue(PinCrypto.sisaKunciMs(5, 0, sekarang) == PinCrypto.KUNCI_MS);
        assertTrue(PinCrypto.sisaKunciMs(6, -1, sekarang) == PinCrypto.KUNCI_MS);
        assertTrue(PinCrypto.sisaKunciMs(4, 0, sekarang) == 0);
    }

    @Test
    public void kunciElapsedLamaDianggapKedaluwarsa() {

        assertTrue(PinCrypto.sisaKunciMs(5, 1_300_000L, 1_700_000_000_000L) == 0);
    }

    @Test
    public void kunciJam1970TetapMengunci() {

        long sekarang = 5_000L;
        long sampai = PinCrypto.kunciBerikutnyaMs(5, sekarang);
        assertTrue(PinCrypto.sisaKunciMs(5, sampai, sekarang) == PinCrypto.KUNCI_MS);
        assertTrue(PinCrypto.sisaKunciMs(5, sampai, sekarang + 60_000L)
                == PinCrypto.KUNCI_MS - 60_000L);
        assertTrue(PinCrypto.sisaKunciMs(5, sampai, sekarang + PinCrypto.KUNCI_MS) == 0);
    }

    @Test
    public void kunciWallBertahanSeolahReboot() {

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
    public void stempelMasaDepanDipangkasSatuKunci() {

        long sekarang = 1_700_000_000_000L;
        long jauh = sekarang + 3L * 365 * 24 * 3600 * 1000;
        assertTrue(PinCrypto.sisaKunciMs(5, jauh, sekarang) == PinCrypto.KUNCI_MS);
        assertTrue(PinCrypto.sisaKunciMs(5, sekarang + 10 * PinCrypto.KUNCI_MS,
                sekarang) == PinCrypto.KUNCI_MS);
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

        assertTrue(PinCrypto.sisaKunciElapsed(500_000L, 10_000L) == 0);
        assertTrue(PinCrypto.sisaKunciElapsed(0L, 10_000L) == 0);
    }

    @Test
    public void stempelElapsedBasiTerdeteksiDanDirebase() {
        assertTrue(PinCrypto.stempelElapsedBasi(500_000L, 10_000L));
        assertFalse(PinCrypto.stempelElapsedBasi(0L, 10_000L));
        assertFalse(PinCrypto.stempelElapsedBasi(100_000L, 90_000L));
        assertFalse(PinCrypto.stempelElapsedBasi(
                10_000L + PinCrypto.KUNCI_MS, 10_000L));
        assertTrue(PinCrypto.stempelElapsedBasi(
                10_000L + PinCrypto.KUNCI_MS + 1, 10_000L));
        assertTrue(PinCrypto.sisaKunciSesudahReboot(120_000L) == 120_000L);
        assertTrue(PinCrypto.sisaKunciSesudahReboot(0L) == PinCrypto.KUNCI_MS);
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

    @Test
    public void hanyaFactoryHilangPermanenYangDiingat() {

        assertTrue(PinCrypto.pabrikHilangPermanen(
                new java.security.NoSuchAlgorithmException("PBKDF2WithHmacSHA256")));

        assertFalse(PinCrypto.pabrikHilangPermanen(new RuntimeException("sibuk")));
        assertFalse(PinCrypto.pabrikHilangPermanen(new OutOfMemoryError("penuh")));
        assertFalse(PinCrypto.pabrikHilangPermanen(null));
    }
}
