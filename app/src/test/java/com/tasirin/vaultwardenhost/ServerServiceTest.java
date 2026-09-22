package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test deteksi binary tak cocok kernel lama (tanpa Android runtime). */
public class ServerServiceTest {

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
    public void domainKosongPakaiOtomatis() {
        assertEquals("http://192.168.1.10:8088",
                ServerService.bangunDomain("", "http", "8088", "192.168.1.10:8088"));
        assertEquals("http://192.168.1.10:8088",
                ServerService.bangunDomain(null, "http", "8088", "192.168.1.10:8088"));
        assertEquals("https://192.168.1.10:8088",
                ServerService.bangunDomain("   ", "https", "8088", "192.168.1.10:8088"));
    }

    @Test
    public void domainHostnameDapatPortAktif() {
        assertEquals("http://vault.lan:8088",
                ServerService.bangunDomain("vault.lan", "http", "8088", "192.168.1.10:8088"));
        assertEquals("http://vault.lan:8088",
                ServerService.bangunDomain("  VAULT.lan  ", "http", "8088", "192.168.1.10:8088"));
        assertEquals("http://server:8088",
                ServerService.bangunDomain("server", "http", "8088", "192.168.1.10:8088"));
    }

    @Test
    public void domainDenganPortSendiriDipakai() {
        assertEquals("http://vault.lan:9000",
                ServerService.bangunDomain("vault.lan:9000", "http", "8088", "192.168.1.10:8088"));
    }

    @Test
    public void domainUrlPenuhDiikutiSkemaAktif() {
        assertEquals("https://vault.lan:8088",
                ServerService.bangunDomain("http://vault.lan/", "https", "8088", "192.168.1.10:8088"));
        assertEquals("http://vault.lan:8088",
                ServerService.bangunDomain("https://vault.lan:8088/path?q=1", "http", "8088",
                        "192.168.1.10:8088"));
    }

    @Test
    public void domainTakValidFallbackOtomatis() {
        assertEquals("http://192.168.1.10:8088",
                ServerService.bangunDomain("va ult.lan", "http", "8088", "192.168.1.10:8088"));
        assertEquals("http://192.168.1.10:8088",
                ServerService.bangunDomain("vault.lan:0", "http", "8088", "192.168.1.10:8088"));
        assertEquals("http://192.168.1.10:8088",
                ServerService.bangunDomain("-salah-.lan", "http", "8088", "192.168.1.10:8088"));
    }

    @Test
    public void ambilDnsHanyaNamaValid() {
        assertEquals("vault.lan", ServerService.ambilDnsDomain("vault.lan"));
        assertEquals("vault.lan", ServerService.ambilDnsDomain("http://vault.lan:9000/"));
        assertNull(ServerService.ambilDnsDomain(""));
        assertNull(ServerService.ambilDnsDomain(null));
        assertNull(ServerService.ambilDnsDomain("192.168.1.10"));
        assertNull(ServerService.ambilDnsDomain("va ult.lan"));
    }

    @Test
    public void outputVersionNormalBukanPanic() {
        assertFalse(ServerService.isKernelRandomPanic("vaultwarden 1.29.2"));
        assertFalse(ServerService.isKernelRandomPanic("vaultwarden 1.37.3\n"));
    }
}
