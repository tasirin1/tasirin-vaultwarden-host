package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test deteksi binary tak cocok kernel lama (tanpa Android runtime). */
public class ServerServiceTest {

    @Test
    public void migrasiPort_toleranSpasi() {
        assertTrue(ServerService.perluMigrasiPort(" 8080 ", false));
        assertFalse(ServerService.perluMigrasiPort(" 8088 ", false));
    }

    @Test
    public void sidikTokenAdmin_konsistenTanpaPlaintext() {
        String a = ServerService.sidikTokenAdmin("rahasia123");
        assertEquals(a, ServerService.sidikTokenAdmin("  rahasia123  "));
        assertFalse(a.isEmpty());
        assertFalse(a.contains("rahasia123"));
        assertFalse(a.equals(ServerService.sidikTokenAdmin("lain456")));
        assertEquals("", ServerService.sidikTokenAdmin(""));
        assertEquals("", ServerService.sidikTokenAdmin(null));
    }

    @Test
    public void potongPesanGalat_takBelahSurrogate() {
        assertEquals("abc", ServerService.potongPesanGalat("abcdef", 3));
        assertEquals("", ServerService.potongPesanGalat(null, 80));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 79; i++) {
            b.append('x');
        }
        b.append(Character.toChars(0x1F600)).append("ekor");
        String r = ServerService.potongPesanGalat(b.toString(), 80);
        assertEquals(79, r.length());
        assertFalse(Character.isHighSurrogate(r.charAt(r.length() - 1)));
    }

    @Test
    public void potongPesanGalat_batasNolKosong() {
        assertEquals("", ServerService.potongPesanGalat("abcdef", 0));
        assertEquals("", ServerService.potongPesanGalat("abcdef", -5));
        assertEquals("", ServerService.potongPesanGalat(null, 0));
    }

    @Test
    public void migrasiPort_hanyaSekaliUntuk8080() {
        assertTrue(ServerService.perluMigrasiPort("8080", false));
        assertFalse(ServerService.perluMigrasiPort("8080", true));
        assertFalse(ServerService.perluMigrasiPort("8088", false));
        assertFalse(ServerService.perluMigrasiPort(null, false));
        assertFalse(ServerService.perluMigrasiPort("", false));
    }

    @Test
    public void bolehBunuhBasi_lewatiSmokeTest() {
        assertTrue(ServerService.bolehBunuhBasi(
                "/data/user/0/com.tasirin.vaultwardenhost/files/bin/vaultwarden-armeabi-v7a"));
        assertFalse(ServerService.bolehBunuhBasi(
                "/data/user/0/com.tasirin.vaultwardenhost/files/bin/vaultwarden-armeabi-v7a --version"));
        assertFalse(ServerService.bolehBunuhBasi(null));
        assertFalse(ServerService.bolehBunuhBasi("/system/bin/sh"));
    }



    @Test
    public void rentangKosong_tanpaAlokasiSubstring() {
        StringBuilder b = new StringBuilder("ab   \nxy");
        assertTrue(ServerService.rentangKosong(b, 2, 5));
        assertFalse(ServerService.rentangKosong(b, 0, 2));
        assertTrue(ServerService.rentangKosong(b, 3, 3));
    }

    @Test
    public void panicGetrandomTerdeteksiDariLogAsliStb() {
        String tail = "thread 'main' panicked at "
                + "/rustc/48a229ceaefd4985c50990b14116b6d856af0985/library/std/src/sys/random/linux.rs:127:25:\n"
                + "failed to generate random data: errno=22, flags=1";
        assertTrue(ServerService.isKernelRandomPanic(tail));
    }

    @Test
    public void formatHostUntukUrl_kurungIpv6() {
        assertEquals("192.168.1.5", ServerService.formatHostUntukUrl("192.168.1.5"));
        assertEquals("127.0.0.1", ServerService.formatHostUntukUrl(""));
        assertEquals("127.0.0.1", ServerService.formatHostUntukUrl(null));
        assertEquals("[::1]", ServerService.formatHostUntukUrl("::1"));
        assertEquals("[2001:db8::1]", ServerService.formatHostUntukUrl("2001:db8::1"));
        assertEquals("[2001:db8::1]", ServerService.formatHostUntukUrl("[2001:db8::1]"));
    }

    @Test
    public void logNormalBukanPanicKernel() {
        assertFalse(ServerService.isKernelRandomPanic("Running (PID 123)\nURL lokal: http://127.0.0.1:8088"));
        assertFalse(ServerService.isKernelRandomPanic(""));
        assertFalse(ServerService.isKernelRandomPanic(null));
    }

    @Test
    public void warningLinkerSajaBukanPanicKernel() {
        assertFalse(ServerService.isKernelRandomPanic(
                "WARNING: linker: vaultwarden-armeabi-v7a: unsupported flags DT_FLAGS_1=0x8000001"));
    }

    @Test
    public void varianPesanGetrandomTetapTerdeteksi() {
        assertTrue(ServerService.isKernelRandomPanic(
                "thread 'main' panicked at src/random.rs:10:\nfailed getrandom syscall"));
    }

    @Test
    public void gagalTicketerTlsTerdeteksiSebagaiPanicKernel() {
        String tail = "Error: Rocket.\n[CAUSE] Bind(\n    Custom {\n"
                + "        kind: Other,\n"
                + "        error: \"bad TLS ticketer: failed to get random bytes\",\n"
                + "    },\n)";
        assertTrue(ServerService.isKernelRandomPanic(tail));
    }

    @Test
    public void varianPesanTicketerTetapTerdeteksi() {
        assertTrue(ServerService.isKernelRandomPanic(
                "bad TLS ticketer: failed to get random bytes"));
        assertTrue(ServerService.isKernelRandomPanic(
                "failed to get random bytes"));
    }

    @Test
    public void noiseLinkerTerdeteksi() {
        assertTrue(ServerService.isNoiseLinker(
                "WARNING: linker: vaultwarden-armeabi-v7a: unsupported flags DT_FLAGS_1=0x8000001"));
        assertTrue(ServerService.isNoiseLinker(
                "  WARNING: linker: libfoo.so: unsupported flags DT_FLAGS_1=0x1"));
    }

    @Test
    public void barisBiasaBukanNoiseLinker() {
        assertFalse(ServerService.isNoiseLinker("vaultwarden 1.37.3"));
        assertFalse(ServerService.isNoiseLinker("failed to get random bytes"));
        assertFalse(ServerService.isNoiseLinker(""));
        assertFalse(ServerService.isNoiseLinker(null));
    }

    @Test
    public void refreshPatchDiperlukanBilaKosongAtauLama() {
        assertTrue(ServerService.perluRefreshPatch(null));
        assertTrue(ServerService.perluRefreshPatch(""));
        assertTrue(ServerService.perluRefreshPatch("1"));
        assertTrue(ServerService.perluRefreshPatch("2"));
    }

    @Test
    public void refreshPatchTidakDiperlukanBilaSudahTerkini() {
        assertFalse(ServerService.perluRefreshPatch(
                String.valueOf(ServerService.BIN_PATCH_REV)));
    }

    @Test
    public void saranCrashExit9MenyebutRamPenuh() {
        String saran = ServerService.saranCrash(9);
        assertTrue(saran.contains("RAM"));
        assertTrue(saran.contains("reboot"));
    }

    @Test
    public void saranCrashExitLainKosong() {
        assertEquals("", ServerService.saranCrash(0));
        assertEquals("", ServerService.saranCrash(1));
        assertEquals("", ServerService.saranCrash(101));
    }

    @Test
    public void catatLogNaikkanVersi() {
        long sebelum = ServerService.logVersion();
        ServerService.catatLog("uji baris");
        assertTrue(ServerService.logVersion() > sebelum);
        ServerService.catatLog(null);
        assertTrue(ServerService.logVersion() > sebelum);
    }

    @Test
    public void outputVersionNormalBukanPanic() {
        assertFalse(ServerService.isKernelRandomPanic("vaultwarden 1.29.2"));
        assertFalse(ServerService.isKernelRandomPanic("vaultwarden 1.37.3\n"));
    }

    @Test
    public void sehatBilaAliveAtauConfig200() {
        assertTrue(ServerService.sehatDariKode(200, -1));
        assertTrue(ServerService.sehatDariKode(500, 200));
        assertTrue(ServerService.sehatDariKode(-1, 200));
        assertTrue(ServerService.sehatDariKode(200, 200));
    }

    @Test
    public void tidakSehatBilaKeduanyaGagal() {
        assertFalse(ServerService.sehatDariKode(-1, -1));
        assertFalse(ServerService.sehatDariKode(500, 500));
        assertFalse(ServerService.sehatDariKode(500, -1));
        assertFalse(ServerService.sehatDariKode(-1, 500));
    }

    @Test
    public void dataDirAmanTolakRootStorage() {
        assertFalse(ServerService.dataDirAman("/sdcard"));
        assertFalse(ServerService.dataDirAman("/sdcard/"));
        assertFalse(ServerService.dataDirAman("/storage/emulated/0"));
        assertFalse(ServerService.dataDirAman("/storage/emulated/0/"));
        assertFalse(ServerService.dataDirAman("/storage/emulated"));
        assertFalse(ServerService.dataDirAman("/storage/sdcard0"));
        assertFalse(ServerService.dataDirAman("/mnt/sdcard"));
        assertFalse(ServerService.dataDirAman("/mnt/media_rw"));
        assertFalse(ServerService.dataDirAman("/mnt/runtime"));
        assertTrue(ServerService.dataDirAman("/sdcard/vaultwarden"));
        assertTrue(ServerService.dataDirAman("/storage/emulated/0/vaultwarden"));
        assertFalse(ServerService.dataDirAman("/mnt/media_rw/vaultwarden"));
        assertFalse(ServerService.dataDirAman("/mnt/runtime/vaultwarden"));
        assertFalse(ServerService.dataDirAman("/storage/self/primary/vaultwarden"));
        assertFalse(ServerService.dataDirAman("/data/data/com.lain/vaultwarden"));
        assertFalse(ServerService.dataDirAman("/data/user/0/com.lain/vaultwarden"));
        assertTrue(ServerService.dataDirAman("/data/data/com.tasirin.vaultwardenhost/vaultwarden"));
        assertTrue(ServerService.dataDirAman("/data/user/0/com.tasirin.vaultwardenhost/vaultwarden"));
    }

    @Test
    public void dataDirAmanTolakPathTerlaluPanjang() {
        StringBuilder sb = new StringBuilder("/sdcard");
        while (sb.length() <= 512) {
            sb.append("/vaultwarden");
        }
        assertFalse(ServerService.dataDirAman(sb.toString()));
        String segmen = new String(new char[256]).replace("\0", "a");
        assertFalse(ServerService.dataDirAman("/sdcard/" + segmen));
        assertTrue(ServerService.dataDirAman("/sdcard/vaultwarden"));
    }

    @Test
    public void dataDirAmanTolakKomaKurawalUntukRocketTls() {
        assertFalse(ServerService.dataDirAman("/sdcard/a,b/c"));
        assertFalse(ServerService.dataDirAman("/sdcard/a{b/c"));
        assertFalse(ServerService.dataDirAman("/sdcard/a}b/c"));
        assertTrue(ServerService.dataDirAman("/sdcard/vaultwarden"));
    }

    @Test
    public void kutipRocketEscapeBackslashDanKutip() {
        assertEquals("", ServerService.kutipRocket(null));
        assertEquals("/sdcard/vaultwarden", ServerService.kutipRocket("/sdcard/vaultwarden"));
        assertEquals("a\\\\b", ServerService.kutipRocket("a\\b"));
        assertEquals("a\\\"b", ServerService.kutipRocket("a\"b"));
    }

    @Test
    public void dataDirAmanTolakTraversalDanSistem() {
        assertFalse(ServerService.dataDirAman("/sdcard/../data"));
        assertFalse(ServerService.dataDirAman("/sdcard/vaultwarden/../../etc"));
        assertFalse(ServerService.dataDirAman("/data/"));
        assertFalse(ServerService.dataDirAman("/system/"));
        assertFalse(ServerService.dataDirAman("/system/fonts"));
        assertFalse(ServerService.dataDirAman("/vendor"));
        assertFalse(ServerService.dataDirAman("/proc/self"));
        assertFalse(ServerService.dataDirAman("/sys/kernel"));
        assertFalse(ServerService.dataDirAman("/dev/null"));
        assertFalse(ServerService.dataDirAman("/data/local/tmp"));
        assertFalse(ServerService.dataDirAman("/sdcard//vaultwarden"));
        assertTrue(ServerService.dataDirAman("/sdcard/my..folder"));
        assertTrue(ServerService.dataDirAman("/sdcard/vaultwarden"));
        assertTrue(ServerService.dataDirAman("/storage/emulated/0/vaultwarden"));
        assertTrue(ServerService.dataDirAman("/data/data/com.tasirin.vaultwardenhost/files"));
        assertTrue(ServerService.dataDirAman("/data/user/0/com.tasirin.vaultwardenhost/files"));
    }

    @Test
    public void dataDirAmanTolakKutipDanRoot() {
        assertTrue(ServerService.dataDirAman("/sdcard/vaultwarden"));
        assertTrue(ServerService.dataDirAman("/storage/emulated/0/vaultwarden"));
        assertFalse(ServerService.dataDirAman(null));
        assertFalse(ServerService.dataDirAman(""));
        assertFalse(ServerService.dataDirAman("sdcard/vaultwarden"));
        assertFalse(ServerService.dataDirAman("/sdcard/a\"b"));
        assertFalse(ServerService.dataDirAman("/"));
        assertFalse(ServerService.dataDirAman("/system"));
        assertEquals(ServerService.DEFAULT_DATA_DIR,
                ServerService.amankanDataDir("/sdcard/a\"b"));
    }

    @Test
    public void normalisasiPortRusakJatuhKeDefault() {
        assertEquals(ServerService.DEFAULT_PORT, ServerService.normalisasiPort(null));
        assertEquals(ServerService.DEFAULT_PORT, ServerService.normalisasiPort(""));
        assertEquals(ServerService.DEFAULT_PORT, ServerService.normalisasiPort("abc"));
        assertEquals(ServerService.DEFAULT_PORT, ServerService.normalisasiPort("0"));
        assertEquals(ServerService.DEFAULT_PORT, ServerService.normalisasiPort("80"));
        assertEquals(ServerService.DEFAULT_PORT, ServerService.normalisasiPort("443"));
        assertEquals(ServerService.DEFAULT_PORT, ServerService.normalisasiPort("1023"));
        assertEquals(ServerService.DEFAULT_PORT, ServerService.normalisasiPort("99999"));
        assertEquals("1024", ServerService.normalisasiPort("1024"));
        assertEquals("8088", ServerService.normalisasiPort("8088"));
        assertEquals("8088", ServerService.normalisasiPort(" 8088 "));
    }

    @Test
    public void dataDirKanonisTolakSistemDanTerimaBiasa() throws Exception {
        assertFalse(ServerService.dataDirKanonisAman(null));
        assertFalse(ServerService.dataDirKanonisAman("/data"));
        assertFalse(ServerService.dataDirKanonisAman("/system/fonts"));
        java.io.File biasa = new java.io.File(
                System.getProperty("java.io.tmpdir"), "uji-kanon-" + System.nanoTime());
        assertTrue(ServerService.dataDirKanonisAman(biasa.getAbsolutePath()));
    }

    @Test
    public void saranLoginKredensialSalah() {
        String s = ServerService.saranLoginUntukBaris(
                "POST /identity/connect/token => 400, Invalid username or password", false);
        assertTrue(s != null && s.startsWith("[login]"));
    }

    @Test
    public void saranLoginTlsMenyebutInstallCa() {
        String s = ServerService.saranLoginUntukBaris(
                "tls handshake failed: certificate unknown", true);
        assertTrue(s != null && s.contains("Install Cert"));
    }

    @Test
    public void saranLoginTlsSelaluHttpsTakSebutHttp() {
        String s = ServerService.saranLoginUntukBaris(
                "ssl handshake failure: alert unknown", false);
        assertTrue(s != null && s.contains("Install Cert"));
        assertTrue(!s.contains("http://"));
    }

    @Test
    public void saranLogin2fa() {
        String s = ServerService.saranLoginUntukBaris("two-factor required 2fa totp", false);
        assertTrue(s != null && s.contains("2FA"));
    }

    @Test
    public void saranLoginNullUntukBarisBiasa() {
        assertNull(ServerService.saranLoginUntukBaris("Rocket has launched", false));
        assertNull(ServerService.saranLoginUntukBaris(null, false));
        assertNull(ServerService.saranLoginUntukBaris("", false));
    }

    @Test
    public void throttleHintLogin60Detik() {
        assertTrue(ServerService.bolehHintLogin(61000, 0));
        assertFalse(ServerService.bolehHintLogin(59000, 0));
    }

    @Test
    public void panduanLoginMemuatUrlDanAkun() {
        java.util.List<String> p1 = ServerService.panduanLoginBitwarden("http", "8088", "192.168.1.5");
        assertEquals(3, p1.size());
        assertTrue(p1.get(0).contains("https://192.168.1.5:8088"));
        assertTrue(p1.get(1).contains("HTTPS WAJIB"));
        assertTrue(p1.get(2).contains("Create Account"));
        java.util.List<String> p2 = ServerService.panduanLoginBitwarden("https", "8088", "192.168.1.5");
        assertTrue(p2.get(0).contains("https://192.168.1.5:8088"));
        assertTrue(p2.get(1).contains("HTTPS WAJIB"));
    }

    @Test
    public void ringkasKodeTampilKodeAtauAlasan() {
        assertEquals("200", ServerService.ringkasKode(200, ""));
        assertEquals("500", ServerService.ringkasKode(500, "x"));
        assertEquals("dilewati", ServerService.ringkasKode(-2, ""));
        assertTrue(ServerService.ringkasKode(-1, "").contains("tak tersambung"));
        assertTrue(ServerService.ringkasKode(-1, "SSLHandshakeException").contains("SSLHandshakeException"));
    }

    @Test
    public void portTakValid_dianggapSibuk() {
        assertTrue(ServerService.isPortBusy(0));
        assertTrue(ServerService.isPortBusy(-1));
        assertTrue(ServerService.isPortBusy(99999));
    }

    @Test
    public void portDirebut_kenaliBindGagal() {
        assertTrue(ServerService.portDirebut("Rocket failed to bind: Address already in use (os error 98)"));
        assertTrue(ServerService.portDirebut("ERROR: bind 0.0.0.0:8088: EADDRINUSE"));
        assertFalse(ServerService.portDirebut("Running (PID 123)\nURL lokal: http://127.0.0.1:8088"));
        assertFalse(ServerService.portDirebut(""));
        assertFalse(ServerService.portDirebut(null));
    }

    @Test
    public void portBebas_takButuhRoot() throws Exception {
        java.net.ServerSocket s = new java.net.ServerSocket(0);
        int bebas;
        try {
            bebas = s.getLocalPort();
        } finally {
            s.close();
        }
        assertFalse(ServerService.portButuhRoot(bebas));
    }

    @Test
    public void portTakValid_takButuhRoot() {
        assertFalse(ServerService.portButuhRoot(0));
        assertFalse(ServerService.portButuhRoot(-1));
        assertFalse(ServerService.portButuhRoot(99999));
    }

    @Test
    public void tokenAdminValid_tolakSpasiDanKontrol() {
        assertTrue(ServerService.tokenAdminValid("abc123-XYZ"));
        assertFalse(ServerService.tokenAdminValid(""));
        assertFalse(ServerService.tokenAdminValid(null));
        assertFalse(ServerService.tokenAdminValid("ada spasi"));
        assertFalse(ServerService.tokenAdminValid("baris\nbaru"));
        assertFalse(ServerService.tokenAdminValid("tab\tsepi"));
    }

    @Test
    public void cacheSesuaiPin_hormatiKuncian() {
        assertTrue(ServerService.cacheSesuaiPin("", "vaultwarden 1.37.3"));
        assertTrue(ServerService.cacheSesuaiPin(null, "vaultwarden 1.37.3"));
        assertTrue(ServerService.cacheSesuaiPin("1.32.0", "vaultwarden 1.32.0"));
        assertTrue(ServerService.cacheSesuaiPin("1.32", "vaultwarden 1.32.0"));
        assertFalse(ServerService.cacheSesuaiPin("1.32.0", "vaultwarden 1.37.3"));
        assertFalse(ServerService.cacheSesuaiPin("1.32.0", "tanpa-versi"));
        assertFalse(ServerService.cacheSesuaiPin("1.32.0", null));
    }

    @Test
    public void portTerikat_loopbackTerdeteksiSibuk() throws Exception {
        java.net.ServerSocket tahan = new java.net.ServerSocket(0);
        try {
            assertTrue(ServerService.isPortBusy(tahan.getLocalPort()));
        } finally {
            tahan.close();
        }
    }
}
