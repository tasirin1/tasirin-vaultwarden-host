package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
        assertFalse(ServerService.bolehBunuhBasi("bin/vaultwarden-tanpa-slash"));
        assertFalse(ServerService.bolehBunuhBasi("/data/app/bin/vaultwarden --version"));
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
    public void configSajaSehatHanyaSaatAlive5xx() {
        assertTrue(ServerService.aliveRusakTapiConfigSehat(500, 200));
        assertTrue(ServerService.aliveRusakTapiConfigSehat(503, 200));
        assertFalse(ServerService.aliveRusakTapiConfigSehat(200, 200));
        assertFalse(ServerService.aliveRusakTapiConfigSehat(-1, 200));
        assertFalse(ServerService.aliveRusakTapiConfigSehat(500, 500));
        assertFalse(ServerService.aliveRusakTapiConfigSehat(500, -1));
        assertFalse(ServerService.aliveRusakTapiConfigSehat(404, 200));
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
    public void dataDirAmanTolakSegmenMultibyte255Byte() {

        StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            emoji.append("\uD83D\uDE00");
        }
        assertFalse(ServerService.dataDirAman("/sdcard/" + emoji.toString()));
        assertTrue(ServerService.dataDirAman("/sdcard/vault\u00E9warden"));
    }

    @Test
    public void dataDirAmanTolakKontrolDanFormatTakTerlihat() {
        assertFalse(ServerService.dataDirAman("/sdcard/vault\u202Ewarden"));
        assertFalse(ServerService.dataDirAman("/sdcard/vault\u200Bwarden"));
        assertFalse(ServerService.dataDirAman("/sdcard/vault\uFEFFwarden"));
        assertFalse(ServerService.dataDirAman("/sdcard/vault\u0007warden"));
        assertFalse(ServerService.dataDirAman("/sdcard/vault\u007Fwarden"));
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
        assertFalse(ServerService.dataDirAman("/sdcard/./vaultwarden"));
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
    public void dataDirBawaanSegarTakKosongDanStabil() {

        String a = ServerService.dataDirBawaanSegar();
        assertNotNull(a);
        assertFalse(a.trim().isEmpty());
        assertTrue(a.endsWith("vaultwarden"));
        assertEquals(a, ServerService.dataDirBawaanSegar());
    }

    @Test
    public void peringkatAntarmuka_lanDiAtasSelulerDanVpn() {
        assertTrue(ServerService.peringkatAntarmuka("wlan0")
                < ServerService.peringkatAntarmuka("rmnet_data0"));
        assertTrue(ServerService.peringkatAntarmuka("eth0")
                < ServerService.peringkatAntarmuka("tun0"));
        assertTrue(ServerService.peringkatAntarmuka("rmnet0")
                < ServerService.peringkatAntarmuka("tun0"));
        assertEquals(0, ServerService.peringkatAntarmuka("wlan1"));
        assertEquals(3, ServerService.peringkatAntarmuka("wg0"));
        assertEquals(3, ServerService.peringkatAntarmuka(null));
    }

    @Test
    public void bakNamaSah_hanyaNamaKelolaAplikasi() {
        String[] data = {"db.sqlite3", "db.sqlite3-wal", "db.sqlite3-shm"};
        assertTrue(ServerService.bakNamaSah("db.sqlite3", data));
        assertTrue(ServerService.bakNamaSah("db.sqlite3-wal", data));
        assertFalse(ServerService.bakNamaSah("cadangan-manual", data));
        assertFalse(ServerService.bakNamaSah("db.sqlite3.bak", data));
        assertFalse(ServerService.bakNamaSah("", data));
        assertFalse(ServerService.bakNamaSah(null, data));
        assertFalse(ServerService.bakNamaSah("db.sqlite3", null));
    }

    @Test
    public void konstantaPortDefaultDijepitLiteral() {
        assertEquals("8088", ServerService.DEFAULT_PORT);
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
    public void portLoopback_tolakKorupTanpaLempar() {

        String dulu = ServerService.runningPort;
        try {
            ServerService.runningPort = "8088";
            assertEquals(8088, new ServerService().portLoopback());
            ServerService.runningPort = "abc";
            assertEquals(-1, new ServerService().portLoopback());
            ServerService.runningPort = "80";
            assertEquals(80, new ServerService().portLoopback());
        } finally {
            ServerService.runningPort = dulu;
        }
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
                "POST /identity/connect/token => 400, Invalid username or password");
        assertTrue(s != null && s.startsWith("[login]"));
    }

    @Test
    public void saranLoginTlsMenyebutInstallCa() {
        String s = ServerService.saranLoginUntukBaris(
                "tls handshake failed: certificate unknown");
        assertTrue(s != null && s.contains("Install Cert"));
    }

    @Test
    public void saranLoginTlsSelaluHttpsTakSebutHttp() {
        String s = ServerService.saranLoginUntukBaris(
                "ssl handshake failure: alert unknown");
        assertTrue(s != null && s.contains("Install Cert"));
        assertTrue(!s.contains("http://"));
    }

    @Test
    public void saranLogin2fa() {
        String s = ServerService.saranLoginUntukBaris("two-factor required 2fa totp");
        assertTrue(s != null && s.contains("2FA"));
    }

    @Test
    public void saranLoginAbaikanLoopback() {

        assertNull(ServerService.saranLoginUntukBaris(
                "tls handshake with 127.0.0.1 failed: certificate unknown"));
        assertNull(ServerService.saranLoginUntukBaris(
                "TLS handshake failed with localhost: alert unknown"));
    }

    @Test
    public void saranLoginNullUntukBarisBiasa() {
        assertNull(ServerService.saranLoginUntukBaris("Rocket has launched"));
        assertNull(ServerService.saranLoginUntukBaris(null));
        assertNull(ServerService.saranLoginUntukBaris(""));
    }

    @Test
    public void minatLoginSekaliJalan() {
        assertTrue(ServerService.minatLogin("Invalid username or password"));
        assertTrue(ServerService.minatLogin("POST /identity/connect/token => 401"));
        assertTrue(ServerService.minatLogin("TWO-FACTOR required"));
        assertTrue(ServerService.minatLogin("need totp code"));
        assertTrue(ServerService.minatLogin("TLS handshake failed"));
        assertTrue(ServerService.minatLogin("ssl alert unknown"));
        assertTrue(ServerService.minatLogin("Certificate verify failed"));
        assertTrue(ServerService.minatLogin("WRONG PASSWORD"));
        assertFalse(ServerService.minatLogin("Rocket has launched"));
        assertFalse(ServerService.minatLogin("GET /alive 200 OK"));
        assertFalse(ServerService.minatLogin(null));
        assertFalse(ServerService.minatLogin(""));
    }

    @Test
    public void throttleHintLogin60Detik() {
        assertTrue(ServerService.bolehHintLogin(61000, 0));
        assertFalse(ServerService.bolehHintLogin(59000, 0));
    }

    @Test
    public void throttlePindaiLogin10Detik() {
        assertTrue(ServerService.bolehPindaiLogin(11000, 0));
        assertFalse(ServerService.bolehPindaiLogin(9000, 0));
        assertEquals(10000, ServerService.LOGIN_PINDAI_JEDA_MS);
    }

    @Test
    public void panduanLoginMemuatUrlDanAkun() {
        java.util.List<String> p1 = ServerService.panduanLoginBitwarden("8088", "192.168.1.5");
        assertEquals(3, p1.size());
        assertTrue(p1.get(0).contains("https://192.168.1.5:8088"));
        assertTrue(p1.get(1).contains("HTTPS WAJIB"));
        assertTrue(p1.get(2).contains("Create Account"));

        java.util.List<String> p2 = ServerService.panduanLoginBitwarden("", null);
        assertEquals(3, p2.size());
        assertTrue(p2.get(0).contains("https://<IP-STB>:" + ServerService.DEFAULT_PORT));
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
        assertFalse(ServerService.cacheSesuaiPin("1.37.3-beta", "vaultwarden 1.37.3"));
        assertFalse(ServerService.cacheSesuaiPin("1.37.3", "vaultwarden 1.37.3-beta"));
        assertTrue(ServerService.cacheSesuaiPin("1.37.3-beta", "vaultwarden 1.37.3-beta"));
    }

    @Test
    public void unduhBolehDicobaBilaBelumPernahGagal() {
        assertTrue(ServerService.bolehCobaUnduhLagi(0, 1000));
        assertTrue(ServerService.bolehCobaUnduhLagi(-5, 1000));
    }

    @Test
    public void hitungRestartBaru_jendelaLimaMenit() {
        java.util.List<Long> riwayat = new java.util.ArrayList<Long>();
        long kini = 10_000_000L;
        riwayat.add(kini - 1000);
        riwayat.add(kini - 200000);
        riwayat.add(kini - 400000);
        assertEquals(2, ServerService.hitungRestartBaru(riwayat, kini));
        riwayat.add(kini - 10000);
        assertEquals(3, ServerService.hitungRestartBaru(riwayat, kini));
        assertEquals(0, ServerService.hitungRestartBaru(
                new java.util.ArrayList<Long>(), kini));
    }

    @Test
    public void cmdlineServer_ketatBukanSmokeTest() {
        assertTrue(ServerService.cmdlineServer(
                "/data/user/0/com.tasirin.vaultwardenhost/files/bin/vaultwarden-armeabi-v7a"));
        assertFalse(ServerService.cmdlineServer(
                "/data/user/0/com.tasirin.vaultwardenhost/files/bin/vaultwarden-armeabi-v7a --version"));
        assertFalse(ServerService.cmdlineServer(null));
        assertFalse(ServerService.cmdlineServer("bin/vaultwarden"));
        assertFalse(ServerService.pidMilikiServer(-1));
    }

    @Test
    public void pidYatimServer_lewatiProsesSendiriDanSmokeTest() {
        String server = "/data/user/0/com.tasirin.vaultwardenhost/files/bin/vaultwarden-armeabi-v7a";
        assertTrue(ServerService.pidYatimServer(1234, 99, 555, server));
        assertTrue(ServerService.pidYatimServer(1234, 99, -1, server));

        assertFalse(ServerService.pidYatimServer(99, 99, 555, server));
        assertFalse(ServerService.pidYatimServer(555, 99, 555, server));
        assertFalse(ServerService.pidYatimServer(-1, 99, 555, server));
        assertFalse(ServerService.pidYatimServer(1234, 99, 555, server + " --version"));
        assertFalse(ServerService.pidYatimServer(1234, 99, 555, "/system/bin/sh"));
        assertFalse(ServerService.pidYatimServer(1234, 99, 555, null));
    }

    @Test
    public void restartDilewati_saatPersiapanAtauServerMati() {
        assertTrue(ServerService.restartDilewati(true, false, false));
        assertTrue(ServerService.restartDilewati(true, true, true));
        assertTrue(ServerService.restartDilewati(false, false, false));
        assertFalse(ServerService.restartDilewati(false, true, true));
        assertFalse(ServerService.restartDilewati(false, false, true));
        assertFalse(ServerService.restartDilewati(false, true, false));
    }

    @Test
    public void pidDariKecocokan_hanyaTunggalYangDiakui() {
        assertEquals(-1, ServerService.pidDariKecocokan(null));
        assertEquals(-1, ServerService.pidDariKecocokan(
                new java.util.ArrayList<Integer>()));
        assertEquals(4321, ServerService.pidDariKecocokan(
                java.util.Collections.singletonList(4321)));
        assertEquals(-1, ServerService.pidDariKecocokan(
                java.util.Arrays.asList(4321, 8765)));
    }

    @Test
    public void ppidDariStat_uraiIndukDariBarisStat() {
        assertEquals(999, ServerService.ppidDariStat(
                "1234 (vaultwarden-armeabi-v7a) S 999 1234 1234 0 -1 4194304"));
        assertEquals(1, ServerService.ppidDariStat(
                "55 (nama proses aneh) R 1 55 55 0 -1 0"));
        assertEquals(42, ServerService.ppidDariStat("7 (a)b) S 42 7 7 0 -1 0"));
        assertEquals(-1, ServerService.ppidDariStat("bukan stat"));
        assertEquals(-1, ServerService.ppidDariStat("123 (x) S"));
        assertEquals(-1, ServerService.ppidDariStat("123 (x) S abc 0"));
        assertEquals(-1, ServerService.ppidDariStat(""));
        assertEquals(-1, ServerService.ppidDariStat(null));
    }

    @Test
    public void startGagalPermanenHanyaSaatTakAdaProsesDanTakDitunda() {

        assertTrue(ServerService.startGagalPermanen(false, false, false, true, false));
        assertFalse(ServerService.startGagalPermanen(true, false, false, true, false));
        assertFalse(ServerService.startGagalPermanen(false, true, false, true, false));
        assertFalse(ServerService.startGagalPermanen(false, false, true, true, false));
        assertFalse(ServerService.startGagalPermanen(true, true, true, true, false));

        assertFalse(ServerService.startGagalPermanen(false, false, false, false, false));

        assertFalse(ServerService.startGagalPermanen(false, false, false, true, true));
    }

    @Test
    public void perluStartSusulanHanyaSaatEpisodeSelesaiDanBelumHidup() {

        assertTrue(ServerService.perluStartSusulan(true, false, false, true));
        assertFalse(ServerService.perluStartSusulan(false, false, false, true));
        assertFalse(ServerService.perluStartSusulan(true, true, false, true));
        assertFalse(ServerService.perluStartSusulan(true, false, true, true));
        assertFalse(ServerService.perluStartSusulan(true, false, false, false));
    }

    @Test
    public void bolehUmumkanJalanHanyaBilaProsesMasihMilikEpisode() {

        assertTrue(ServerService.bolehUmumkanJalan(true, true, true, true));

        assertFalse(ServerService.bolehUmumkanJalan(false, true, true, true));
        assertFalse(ServerService.bolehUmumkanJalan(true, false, true, true));
        assertFalse(ServerService.bolehUmumkanJalan(true, true, false, true));
        assertFalse(ServerService.bolehUmumkanJalan(true, true, true, false));
        assertFalse(ServerService.bolehUmumkanJalan(false, false, false, false));
    }

    @Test
    public void fallbackTlsTakMenulisKunciKeStoragePublik() {

        assertTrue(ServerService.diStoragePublik("/sdcard/vaultwarden/tls"));
        assertTrue(ServerService.diStoragePublik("/storage/emulated/0/vw/tls"));
        assertTrue(ServerService.diStoragePublik("/mnt/media_rw/1234/tls"));

        assertTrue(ServerService.diStoragePublik(null));
        assertFalse(ServerService.diStoragePublik("/data/data/com.tasirin.vaultwardenhost/files/tls"));
        assertFalse(ServerService.diStoragePublik("/data/user/0/com.tasirin.vaultwardenhost/files/tls"));
        assertFalse(ServerService.bolehFallbackTls(true, false));
        assertTrue(ServerService.bolehFallbackTls(true, true));
        assertTrue(ServerService.bolehFallbackTls(false, false));
    }

    private static final class ProsesPalsu extends Process {
        @Override public java.io.OutputStream getOutputStream() {
            return null;
        }

        @Override public java.io.InputStream getInputStream() {
            return null;
        }

        @Override public java.io.InputStream getErrorStream() {
            return null;
        }

        @Override public int waitFor() {
            return 0;
        }

        @Override public int exitValue() {
            return 0;
        }

        @Override public void destroy() {
        }
    }

    @Test
    public void lepasProsesTakMenghapusProsesStartBaru() throws Exception {
        java.lang.reflect.Field f = ServerService.class.getDeclaredField("process");
        f.setAccessible(true);
        Object asli = f.get(null);
        try {
            Process lama = new ProsesPalsu();
            Process baru = new ProsesPalsu();
            boolean[] efek = {false};
            f.set(null, lama);
            assertTrue(ServerService.lepasProses(lama, () -> efek[0] = true));
            assertTrue("efek pembersihan wajib jalan saat proses aktif", efek[0]);
            assertNull("proses aktif wajib dilepas", f.get(null));

            assertFalse(ServerService.lepasProses(lama, () -> efek[0] = false));
            assertTrue(efek[0]);

            f.set(null, baru);
            assertFalse(ServerService.lepasProses(lama, null));
            assertEquals(baru, f.get(null));
            assertFalse(ServerService.lepasProses(null, null));
        } finally {
            f.set(null, asli);
        }
    }

    @Test
    public void pindahLogFileMenulisBufferKeFileLama() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("vwlog").toFile();
        java.io.File lama = new java.io.File(dir, "lama.log");
        java.io.File baru = new java.io.File(dir, "baru.log");
        java.lang.reflect.Field fLog = ServerService.class.getDeclaredField("logFile");
        fLog.setAccessible(true);
        java.lang.reflect.Field fBuf = ServerService.class.getDeclaredField("logFileBuf");
        fBuf.setAccessible(true);
        Object asliLog = fLog.get(null);
        StringBuilder buf = (StringBuilder) fBuf.get(null);
        try {
            fLog.set(null, lama);
            buf.append("baris-folder-lama\n");
            ServerService.pindahLogFile(baru);

            assertEquals(baru, fLog.get(null));
            assertEquals(0, buf.length());
            String isi = new String(java.nio.file.Files.readAllBytes(lama.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(isi.contains("baris-folder-lama"));
            assertFalse(baru.exists());
        } finally {
            fLog.set(null, asliLog);
            buf.setLength(0);
        }
    }

    @Test
    public void teksNotifikasiIkutStatusDanTakMeluber() {

        assertEquals("Server aktif di port 8080",
                ServerService.teksNotifikasi(true, "8080", "apa saja"));
        assertEquals("Server aktif di port ?",
                ServerService.teksNotifikasi(true, null, ""));

        assertEquals("Server crash - restart dalam 2 dtk",
                ServerService.teksNotifikasi(false, "8080",
                        "Server crash - restart dalam 2 dtk\n" + "tail " + "x".repeat(300)));
        assertEquals("Menjalankan tugas latar...",
                ServerService.teksNotifikasi(false, "8080", "   \n  "));
        assertEquals("Menjalankan tugas latar...",
                ServerService.teksNotifikasi(false, "8080", null));

        String panjang = "y".repeat(200);
        String hasil = ServerService.teksNotifikasi(false, "8080", panjang);
        assertEquals(80, hasil.length());
        assertTrue(hasil.endsWith("..."));
    }

    @Test
    public void manualPerluValidasiHanyaSaatSidikFileBerubah() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("vwmanual").toFile();
        java.io.File bin = new java.io.File(dir, "vaultwarden-" + ServerService.ABI);

        assertFalse(ServerService.manualPerluValidasi(null, "", bin));
        assertFalse(ServerService.manualPerluValidasi("", "", bin));

        assertFalse(ServerService.manualPerluValidasi("abc", "", bin));
        java.nio.file.Files.write(bin.toPath(), new byte[]{1, 2, 3});
        String sidik = ServerService.sidikManualFile(bin);
        assertFalse(sidik.isEmpty());

        assertFalse(ServerService.manualPerluValidasi("abc", sidik, bin));

        java.nio.file.Files.write(bin.toPath(), new byte[]{4, 5, 6, 7});
        assertTrue(ServerService.manualPerluValidasi("abc", sidik, bin));

        assertTrue(bin.delete());
        assertTrue(ServerService.manualPerluValidasi("abc", sidik, bin));
        assertEquals("", ServerService.sidikManualFile(null));
        assertEquals("", ServerService.sidikManualFile(bin));
    }

    @Test
    public void unduhBolehDicobaBilaJamMundur() {

        assertTrue(ServerService.bolehCobaUnduhLagi(10_000_000L, 1000));
    }

    @Test
    public void unduhDitundaBilaBaruGagal() {
        long sekarang = 10_000_000L;
        assertFalse(ServerService.bolehCobaUnduhLagi(sekarang - 1000, sekarang));
        assertTrue(ServerService.bolehCobaUnduhLagi(
                sekarang - ServerService.TUNDA_ULANG_UNDUH_MS, sekarang));
        assertTrue(ServerService.bolehCobaUnduhLagi(
                sekarang - ServerService.TUNDA_ULANG_UNDUH_MS - 1, sekarang));
    }

    @Test
    public void normalisasiPort_tanpaEksepsiUntukInputAneh() {
        assertEquals("8088", ServerService.normalisasiPort("8088"));
        assertEquals("8088", ServerService.normalisasiPort("  8088  "));
        assertEquals("8088", ServerService.normalisasiPort("+8088"));
        assertEquals(ServerService.DEFAULT_PORT, ServerService.normalisasiPort("abc"));
        assertEquals(ServerService.DEFAULT_PORT, ServerService.normalisasiPort(""));
        assertEquals(ServerService.DEFAULT_PORT, ServerService.normalisasiPort(null));
        assertEquals(ServerService.DEFAULT_PORT, ServerService.normalisasiPort("12.5"));
        assertEquals(ServerService.DEFAULT_PORT, ServerService.normalisasiPort("99999999"));
        assertEquals(ServerService.DEFAULT_PORT, ServerService.normalisasiPort("80"));
        assertEquals(ServerService.DEFAULT_PORT, ServerService.normalisasiPort("0x50"));
        assertTrue(ServerService.portTampakAngka("8088"));
        assertTrue(ServerService.portTampakAngka("+8088"));
        assertFalse(ServerService.portTampakAngka("8a88"));
        assertFalse(ServerService.portTampakAngka(""));
        assertFalse(ServerService.portTampakAngka(null));
        assertFalse(ServerService.portTampakAngka("1234567"));
    }

    @Test
    public void ipCacheSegar_enamPuluhDetik() {
        assertTrue(ServerService.ipCacheSegar(65_000, 10_000, "192.168.1.2"));
        assertFalse(ServerService.ipCacheSegar(75_000, 10_000, "192.168.1.2"));
        assertFalse(ServerService.ipCacheSegar(10_000, 10_000, ""));
        assertFalse(ServerService.ipCacheSegar(10_000, 10_000, null));
        assertFalse(ServerService.ipCacheSegar(5_000, 10_000, "192.168.1.2"));
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

    static class ProsesMati extends Process {
        @Override public java.io.OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
        }
        @Override public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }
        @Override public java.io.InputStream getErrorStream() {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }
        @Override public int waitFor() {
            return 0;
        }
        @Override public int exitValue() {
            return 0;
        }
        @Override public void destroy() {
        }
    }

    @Test
    public void tungguProsesMatiCepat() throws Exception {
        assertTrue(ServerService.waitForOrKill(new ProsesMati(), 2000));
    }

    @Test
    public void tungguProsesMacetTimeout() throws Exception {
        Process macet = new ProsesMati() {
            @Override public int waitFor() {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 0;
            }
            @Override public int exitValue() {
                throw new IllegalThreadStateException();
            }
        };
        long mulai = System.nanoTime();
        assertFalse(ServerService.waitForOrKill(macet, 300));

        assertTrue(System.nanoTime() - mulai < 10_000_000_000L);
    }

    @Test
    public void matikanProsesTegas_prosesMatiAtauNullAman() {
        final java.util.concurrent.atomic.AtomicInteger destroy =
                new java.util.concurrent.atomic.AtomicInteger();
        Process mati = new ProsesMati() {
            @Override public void destroy() {
                destroy.incrementAndGet();
            }
        };
        ServerService.matikanProsesTegas(mati, 200);
        assertEquals(1, destroy.get());
        ServerService.matikanProsesTegas(null, 200);
        assertEquals(1, destroy.get());
    }

    @Test
    public void matikanProsesTegas_macetDipaksaMati() {
        final java.util.concurrent.atomic.AtomicInteger destroy =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CountDownLatch lepas =
                new java.util.concurrent.CountDownLatch(1);
        Process macet = new ProsesMati() {
            @Override public int waitFor() {
                try {
                    lepas.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 0;
            }
            @Override public void destroy() {
                if (destroy.incrementAndGet() >= 2) {
                    lepas.countDown();
                }
            }
        };
        ServerService.matikanProsesTegas(macet, 100);

        assertEquals(2, destroy.get());
    }

    @Test
    public void kunciDataMemblokirStartSampaiRestoreSelesai() throws Exception {
        final java.util.concurrent.CountDownLatch masuk =
                new java.util.concurrent.CountDownLatch(1);
        ServerService.kunciData();
        java.util.concurrent.CompletableFuture<Void> f =
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    ServerService.kunciData();
                    try {
                        masuk.countDown();
                    } finally {
                        ServerService.lepasKunciData();
                    }
                });
        try {

            assertFalse(masuk.await(150, java.util.concurrent.TimeUnit.MILLISECONDS));
        } finally {
            ServerService.lepasKunciData();
        }
        assertTrue(masuk.await(2, java.util.concurrent.TimeUnit.SECONDS));
        f.get(2, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void tulisElfArm(java.io.File f) throws Exception {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "rw")) {
            r.setLength(1_200_000);
            byte[] h = new byte[20];
            h[0] = 0x7F;
            h[1] = 'E';
            h[2] = 'L';
            h[3] = 'F';
            h[4] = 1;
            h[5] = 1;
            h[18] = 40;
            r.write(h);
        }
    }

    @Test
    public void pulihkanBakBiner_hanyaSaatAktifRusak() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("vwbak").toFile();
        java.io.File out = new java.io.File(dir, "vaultwarden-armeabi-v7a");
        java.io.File bak = new java.io.File(dir, "vaultwarden-armeabi-v7a.bak");

        tulisElfArm(out);
        tulisElfArm(bak);
        assertFalse(ServerService.pulihkanBakBiner(out, bak));
        assertTrue(bak.isFile());

        java.nio.file.Files.write(out.toPath(), new byte[]{1, 2, 3});
        assertTrue(ServerService.pulihkanBakBiner(out, bak));
        assertFalse(bak.exists());
        assertTrue(out.length() >= 1_000_000);

        java.nio.file.Files.write(out.toPath(), new byte[]{1, 2, 3});
        java.nio.file.Files.write(bak.toPath(), new byte[]{4, 5, 6, 7});
        assertFalse(ServerService.pulihkanBakBiner(out, bak));
        assertTrue(bak.exists());
        assertEquals(3, out.length());
    }

    @Test
    public void pingMilikEpisodeSama_tolakProsesAtauEpisodeBeda() {
        Process a = new ProsesPalsu();
        Process b = new ProsesPalsu();
        assertTrue(ServerService.pingMilikEpisodeSama(a, a, 3, 3));

        assertFalse(ServerService.pingMilikEpisodeSama(a, b, 3, 3));

        assertFalse(ServerService.pingMilikEpisodeSama(a, a, 3, 4));

        assertFalse(ServerService.pingMilikEpisodeSama(null, null, 3, 3));
        assertFalse(ServerService.pingMilikEpisodeSama(a, a, -1, -1));
    }
}
