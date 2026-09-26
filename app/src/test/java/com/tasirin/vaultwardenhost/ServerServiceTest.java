package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test deteksi binary tak cocok kernel lama (tanpa Android runtime). */
public class ServerServiceTest {
    @Test
    public void migrasiPort_hanyaSekaliUntuk8080() {
        assertTrue(ServerService.perluMigrasiPort("8080", false));
        assertFalse(ServerService.perluMigrasiPort("8080", true));
        assertFalse(ServerService.perluMigrasiPort("8088", false));
        assertFalse(ServerService.perluMigrasiPort(null, false));
        assertFalse(ServerService.perluMigrasiPort("", false));
    }

    @Test
    public void barisKosong_tanpaAlokasiTrim() {
        assertTrue(ServerService.barisKosong(null));
        assertTrue(ServerService.barisKosong(""));
        assertTrue(ServerService.barisKosong("   \t "));
        assertFalse(ServerService.barisKosong("log"));
        assertFalse(ServerService.barisKosong("  x "));
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
        assertTrue(ServerService.dataDirAman("/sdcard/vaultwarden"));
        assertTrue(ServerService.dataDirAman("/storage/emulated/0/vaultwarden"));
        assertTrue(ServerService.dataDirAman("/data/data/com.tasirin.vaultwardenhost/files"));
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
        assertEquals(ServerService.DEFAULT_PORT, ServerService.normalisasiPort("99999"));
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
    public void ringkasKodeTampilKodeAtauAlasan() {
        assertEquals("200", ServerService.ringkasKode(200, ""));
        assertEquals("500", ServerService.ringkasKode(500, "x"));
        assertEquals("dilewati", ServerService.ringkasKode(-2, ""));
        assertTrue(ServerService.ringkasKode(-1, "").contains("tak tersambung"));
        assertTrue(ServerService.ringkasKode(-1, "SSLHandshakeException").contains("SSLHandshakeException"));
    }
}
