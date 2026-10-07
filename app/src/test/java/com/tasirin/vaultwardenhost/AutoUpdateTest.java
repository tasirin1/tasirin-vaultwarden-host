package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test throttle auto-update (tanpa Android runtime). */
public class AutoUpdateTest {

    @Test
    public void lewatiBilaBaruGagalDanCacheAda() {
        long sekarang = 10_000_000L;
        assertTrue(AutoUpdate.bolehLewatiCobaLagi(true, sekarang - 1000, sekarang));
    }

    @Test
    public void cobaLagiBilaJedaTerlampaui() {
        long sekarang = 10_000_000L;
        assertFalse(AutoUpdate.bolehLewatiCobaLagi(true,
                sekarang - ServerService.TUNDA_ULANG_UNDUH_MS - 1, sekarang));
    }

    @Test
    public void tetapCobaBilaTanpaCache() {
        long sekarang = 10_000_000L;
        assertFalse(AutoUpdate.bolehLewatiCobaLagi(false, sekarang - 1000, sekarang));
        assertFalse(AutoUpdate.bolehLewatiCobaLagi(false, 0, sekarang));
    }

    @Test
    public void tawarkanBaruSekaliPerVersi() {
        assertTrue(AutoUpdate.tawarkanBaru("", "1.37.4"));
        assertFalse(AutoUpdate.tawarkanBaru("1.37.4", "1.37.4"));
        assertFalse(AutoUpdate.tawarkanBaru("v1.37.4", "1.37.4"));
        assertTrue(AutoUpdate.tawarkanBaru("1.37.3", "1.37.4"));
    }

    @Test
    public void tetapCobaBilaBelumPernahGagal() {
        assertFalse(AutoUpdate.bolehLewatiCobaLagi(true, 0, 10_000_000L));
    }
}
