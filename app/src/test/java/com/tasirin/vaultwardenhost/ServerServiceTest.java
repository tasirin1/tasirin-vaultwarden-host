package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertFalse;
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
    public void outputVersionNormalBukanPanic() {
        assertFalse(ServerService.isKernelRandomPanic("vaultwarden 1.29.2"));
        assertFalse(ServerService.isKernelRandomPanic("vaultwarden 1.37.3\n"));
    }
}
