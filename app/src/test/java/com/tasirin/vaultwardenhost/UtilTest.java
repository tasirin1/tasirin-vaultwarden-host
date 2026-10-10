package com.tasirin.vaultwardenhost;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.File;

public class UtilTest {
    @Test
    public void cocokChatNumerik() {
        assertTrue(Util.cocokChat("12345", 12345, ""));
        assertTrue(Util.cocokChat(" 12345 ", 12345, "lain"));
        assertFalse(Util.cocokChat("12345", 999, ""));
        assertFalse(Util.cocokChat("", 12345, ""));
    }

    @Test
    public void cocokChatUsernameNumerikDiperlakukanSebagaiId() {
        assertTrue(Util.cocokChat("@12345", 12345, "penyerang"));
        assertFalse(Util.cocokChat("@12345", 999, "12345"));
    }

    @Test
    public void angkaBulatDeteksiTanpaEksepsi() {
        assertTrue(Util.angkaBulat("12345"));
        assertTrue(Util.angkaBulat("-100123"));
        assertTrue(Util.angkaBulat("+123"));
        assertFalse(Util.angkaBulat("nama"));
        assertFalse(Util.angkaBulat(""));
        assertFalse(Util.angkaBulat(null));
        assertFalse(Util.angkaBulat("+"));
        assertFalse(Util.angkaBulat("12.5"));
    }

    @Test
    public void cocokChatUsernameTanpaRegex() {
        assertTrue(Util.cocokChat("@nama", 1, "@nama"));
        assertTrue(Util.cocokChat("@nama", 1, "nama"));
        assertTrue(Util.cocokChat("nama", 1, "@nama"));
        assertFalse(Util.cocokChat("@nama", 1, "@lain"));
        assertFalse(Util.cocokChat("bukan-angka", 1, "lain"));
    }

    @Test
    public void cocokChatTolakNolDepan() {
        assertFalse(Util.cocokChat("0123", 123, ""));
        assertFalse(Util.cocokChat("00123", 123, ""));
        assertFalse(Util.cocokChat("@0123", 123, "0123"));
        assertFalse(Util.cocokChat("+0123", 123, ""));
        assertTrue(Util.cocokChat("0", 0, ""));
        assertTrue(Util.cocokChat("123", 123, ""));
    }

    @Test
    public void cocokChatIdBerawalanPlus() {
        assertTrue(Util.cocokChat("+12345", 12345, ""));
        assertTrue(Util.cocokChat("  +12345 ", 12345, "lain"));
        assertFalse(Util.cocokChat("+", 12345, ""));
        assertFalse(Util.cocokChat("+12345", 999, ""));
    }

    @Test
    public void chatAdalahGrupHanyaIdNegatif() {
        assertTrue(Util.chatAdalahGrup("-100123"));
        assertTrue(Util.chatAdalahGrup("  -1 "));
        assertFalse(Util.chatAdalahGrup("12345"));
        assertFalse(Util.chatAdalahGrup("+12345"));
        assertFalse(Util.chatAdalahGrup("@grup"));
        assertFalse(Util.chatAdalahGrup("nama"));
        assertFalse(Util.chatAdalahGrup(""));
        assertFalse(Util.chatAdalahGrup(null));
    }

    @Test
    public void chatPerluAnggapGrupFailClosedUntukUsername() {
        assertTrue(Util.chatPerluAnggapGrup("-100123"));
        assertTrue(Util.chatPerluAnggapGrup("@grup"));
        assertTrue(Util.chatPerluAnggapGrup("nama"));
        assertFalse(Util.chatPerluAnggapGrup("12345"));
        assertFalse(Util.chatPerluAnggapGrup("+12345"));
        assertFalse(Util.chatPerluAnggapGrup("@12345"));
        assertFalse(Util.chatPerluAnggapGrup(""));
        assertFalse(Util.chatPerluAnggapGrup(null));
    }

    @Test
    public void cocokChatUsername() {
        assertTrue(Util.cocokChat("@nama", 999, "nama"));
        assertTrue(Util.cocokChat("nama", 999, "@NAMA"));
        assertFalse(Util.cocokChat("@nama", 999, "lain"));
        assertFalse(Util.cocokChat("@nama", 999, null));
    }

    @Test
    public void pesanSegarToleranMasaDepan() {
        long kini = 1_000_000L;
        assertFalse(Util.pesanSegar(kini - 400_000L, kini, 300_000L));
        assertTrue(Util.pesanSegar(kini - 100_000L, kini, 300_000L));
        assertTrue(Util.pesanSegar(kini + 60_000L, kini, 300_000L));
        assertFalse(Util.pesanSegar(kini + 400_000L, kini, 300_000L));
        assertFalse(Util.pesanSegar(0, kini, 300_000L));
    }

    @Test
    public void tolakRedirectHttp() {
        assertTrue(Util.bolehIkutiRedirect("https://a/b", "https://c/d"));
        assertTrue(Util.bolehIkutiRedirect("https://a/b", "/d"));
        assertFalse(Util.bolehIkutiRedirect("https://a/b", "http://c/d"));
        assertFalse(Util.bolehIkutiRedirect("https://a/b", ""));
    }

    @Test
    public void redirectProtokolRelatifTetapHttps() {
        assertTrue(Util.bolehIkutiRedirect("https://a/b", "//a/d"));
        assertEquals("https://c/d", Util.sambungRedirect("https://a/b", "//c/d"));
    }

    @Test
    public void redirectGithubHanyaHostGithub() {
        assertTrue(Util.bolehIkutiRedirectGithub(
                "https://github.com/a/b", "https://objects.githubusercontent.com/c"));
        assertTrue(Util.bolehIkutiRedirectGithub(
                "https://github.com/a/b", "/c/d"));
        assertFalse(Util.bolehIkutiRedirectGithub(
                "https://github.com/a/b", "https://jahat.example/c"));
        assertFalse(Util.bolehIkutiRedirectGithub(
                "https://github.com/a/b", "http://objects.githubusercontent.com/c"));
        assertTrue(Util.hostGithubAman("github.com"));
        assertTrue(Util.hostGithubAman("objects.githubusercontent.com"));
        assertTrue(Util.hostGithubAman("release-assets.githubusercontent.com"));
        assertFalse(Util.hostGithubAman("gist.githubusercontent.com"));
        assertFalse(Util.hostGithubAman("raw.githubusercontent.com"));
        assertFalse(Util.hostGithubAman("jahat.example"));
        assertFalse(Util.hostGithubAman("github.com.jahat.example"));
        // Otoritas "//host" membawa ":port" mentah: redirect sah berport
        // eksplisit wajib diterima, bukan ditolak.
        assertTrue(Util.hostGithubAman("github-production-release-asset-abc123.githubusercontent.com"));
        assertFalse(Util.hostGithubAman("evil-github-production-release-asset-abc.githubusercontent.com.evil.example"));
        assertTrue(Util.hostGithubAman("github.com:443"));
        assertTrue(Util.hostGithubAman("objects.githubusercontent.com:443"));
        assertFalse(Util.hostGithubAman("github.com:abc"));
        assertFalse(Util.hostGithubAman(":443"));
        assertTrue(Util.bolehIkutiRedirectGithub(
                "https://github.com/a/b", "//github.com:443/c"));
        assertTrue(Util.bolehIkutiRedirectGithub(
                "https://github.com/a/b", "https://github.com:443/c"));
    }

    @Test
    public void redirectTelegramHanyaHostTelegram() {
        assertTrue(Util.bolehIkutiRedirectTelegram(
                "https://api.telegram.org/f", "https://api.telegram.org/g"));
        assertTrue(Util.bolehIkutiRedirectTelegram(
                "https://api.telegram.org/f", "/g/h"));
        assertTrue(Util.bolehIkutiRedirectTelegram(
                "https://api.telegram.org/f", "//api.telegram.org:443/g"));
        assertFalse(Util.bolehIkutiRedirectTelegram(
                "https://api.telegram.org/f", "https://jahat.example/g"));
        assertFalse(Util.bolehIkutiRedirectTelegram(
                "https://api.telegram.org/f", "http://api.telegram.org/g"));
        assertFalse(Util.bolehIkutiRedirectTelegram(
                "https://api.telegram.org/f", "https://api.telegram.org.jahat.example/g"));
        assertTrue(Util.hostTelegramAman("api.telegram.org"));
        assertTrue(Util.hostTelegramAman("cdn1.cdn-telegram.org"));
        assertFalse(Util.hostTelegramAman("jahat.example"));
        assertFalse(Util.hostTelegramAman("telegram.org.jahat.example"));
        assertFalse(Util.hostTelegramAman(null));
    }

    @Test
    public void redirectProtokolRelatifHanyaSehost() {
        assertTrue(Util.bolehIkutiRedirect("https://a/b", "//a/d"));
        assertTrue(Util.bolehIkutiRedirect("https://a/b", "//A:443/d?x=1"));
        assertFalse(Util.bolehIkutiRedirect("https://a/b", "//c/d"));
        assertFalse(Util.bolehIkutiRedirect("https://a/b", "//evil-a.com/d"));
        assertFalse(Util.bolehIkutiRedirect("::bukan-url::", "//a/d"));
        assertTrue(Util.hostSama("https://a/b", "a/d"));
        assertFalse(Util.hostSama("https://a/b", "c/d"));
    }

    @Test
    public void sambungRedirectDasarRusakTahanDiDasar() {
        String rusak = "::bukan-url::";
        assertEquals(rusak, Util.sambungRedirect(rusak, "/x"));
        assertEquals(rusak, Util.sambungRedirect(rusak, "relatif"));
        assertEquals("https://c/d", Util.sambungRedirect(rusak, "https://c/d"));
        assertEquals("https://h:1/a", Util.sambungRedirect("https://h:1/a", null));
    }

    @Test
    public void sambungRedirectRelatif() {
        assertEquals("https://h:1/x", Util.sambungRedirect("https://h:1/a", "/x"));
        assertEquals("https://c/d", Util.sambungRedirect("https://a/b", "https://c/d"));
        assertEquals("HTTPS://c/d", Util.sambungRedirect("https://a/b", "HTTPS://c/d"));
    }

    @Test
    public void sambungRedirectTolakDowngradeHttp() {
        // Absolut http:// fail-closed ke dasar (anti downgrade cleartext),
        // bukan diteruskan seperti https://.
        assertEquals("https://a/b", Util.sambungRedirect("https://a/b", "http://c/d"));
        assertEquals("https://a/b", Util.sambungRedirect("https://a/b", "HTTP://c/d"));
    }

    @Test
    public void batasUnzip() throws Exception {
        long t = Util.tambahUkuranUnzip(0, 10, 100, 1, 10);
        assertEquals(10, t);
        try {
            Util.tambahUkuranUnzip(90, 20, 100, 1, 10);
            fail("harus lempar batas ukuran");
        } catch (java.io.IOException diharapkan) {
        }
        try {
            Util.tambahUkuranUnzip(0, 1, 100, 11, 10);
            fail("harus lempar batas entri");
        } catch (java.io.IOException diharapkan) {
        }
    }

    @Test
    public void hostSamaDukungIpv6KurungSiku() {
        assertTrue(Util.hostSama("http://[2001:db8::1]:8080/a", "[2001:db8::1]/b"));
        assertTrue(Util.hostSama("http://[2001:db8::1]/a", "[2001:db8::1]:8080/b?x=1"));
        assertTrue(Util.hostSama("http://[::1]/a", "[::1]/b"));
        assertFalse(Util.hostSama("http://[2001:db8::1]/a", "[2001:db8::2]/b"));
        assertFalse(Util.hostSama("http://[2001:db8::1]/a", "[2001:db8::1"));
        assertTrue(Util.hostSama("https://a/b", "a:443/d"));
        assertFalse(Util.hostSama("https://a/b", ":443/d"));
    }

    @Test
    public void kupasHostPortPisahPortTanpaRusakIpv6() {
        assertEquals("contoh.com", Util.kupasHostPort("contoh.com"));
        assertEquals("contoh.com", Util.kupasHostPort("contoh.com:8080"));
        assertEquals("2001:db8::1", Util.kupasHostPort("[2001:db8::1]"));
        assertEquals("2001:db8::1", Util.kupasHostPort("[2001:db8::1]:8080"));
        assertEquals("2001:db8::1", Util.kupasHostPort("2001:db8::1"));
        assertEquals(null, Util.kupasHostPort("[2001:db8::1"));
        assertEquals(null, Util.kupasHostPort(""));
        assertEquals(null, Util.kupasHostPort(null));
    }

    @Test
    public void normalisasiHostBuangKurungSiku() {
        assertEquals("::1", Util.normalisasiHost("[::1]"));
        assertEquals("a", Util.normalisasiHost("a"));
        assertEquals("", Util.normalisasiHost(null));
    }

    @Test
    public void amanTrimTanpaNpe() {
        assertEquals("", Util.amanTrim(null));
        assertEquals("", Util.amanTrim("   "));
        assertEquals("abc", Util.amanTrim("  abc  "));
    }

    @Test
    public void pecahPinTanpaRegex() {
        String[] r = Util.pecahPin("PBKDF2$30000$abcdef$1234");
        assertNotNull(r);
        assertEquals(4, r.length);
        assertEquals("PBKDF2", r[0]);
        assertEquals("30000", r[1]);
        assertNull(Util.pecahPin("tanpa-pemisah"));
        assertNull(Util.pecahPin("a$b$c$d$e"));
        assertNull(Util.pecahPin(null));
    }

    @Test
    public void cocokAsciiJalurCepat() {
        assertTrue(Util.cocokAscii("Token=abc", "token"));
        assertTrue(Util.cocokAscii("BOT123", "bot"));
        assertFalse(Util.cocokAscii("info biasa", "token"));
        assertFalse(Util.cocokAscii("pendek", "kalimat panjang sekali"));
    }

    @Test
    public void mengandungAbaikanHurufTanpaAlokasi() {
        assertTrue(Util.mengandungAbaikanHuruf("Login Failed bro", "login failed"));
        assertTrue(Util.mengandungAbaikanHuruf("TLS Handshake GAGAL", "tls"));
        assertFalse(Util.mengandungAbaikanHuruf("info biasa", "invalid"));
        assertFalse(Util.mengandungAbaikanHuruf("pendek", "kalimat panjang sekali"));
    }

    @Test
    public void hapusHanyaTempInternal() throws Exception {
        File base = new File(System.getProperty("java.io.tmpdir"), "uji-util-" + System.nanoTime());
        File cache = new File(base, "cache");
        File files = new File(base, "files");
        cache.mkdirs();
        files.mkdirs();
        File dalam = new File(cache, "a.zip");
        dalam.createNewFile();
        assertTrue(Util.bolehHapusFile(cache, files, dalam));
        File luar = new File(base, "luar.zip");
        luar.createNewFile();
        assertFalse(Util.bolehHapusFile(cache, files, luar));
        dalam.delete();
        luar.delete();
        cache.delete();
        files.delete();
        base.delete();
    }
}
