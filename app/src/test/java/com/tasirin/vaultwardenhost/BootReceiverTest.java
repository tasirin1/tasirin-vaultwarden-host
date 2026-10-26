package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test logika murni BootReceiver — tanpa Android runtime. */
public class BootReceiverTest {

    /** onReceive hanya men-throttle BOOT_COMPLETED; MY_PACKAGE_REPLACED adalah
     *  protected broadcast sistem sehingga tak bisa dipalsukan app lain. */
    @Test
    public void onReceive_throttleHanyaBootBukanGantiPaket() {
        assertTrue(BootReceiver.perluThrottleBoot("android.intent.action.BOOT_COMPLETED"));
        assertFalse(BootReceiver.perluThrottleBoot("android.intent.action.MY_PACKAGE_REPLACED"));
        assertFalse(BootReceiver.perluThrottleBoot(BootReceiver.AKSI_GANTI_PAKET));
        // Aksi lain ditolak lebih awal di onReceive; helper tak perlu tahu.
        assertTrue(BootReceiver.perluThrottleBoot(null));
    }

    @Test
    public void gantiPaket_tetapJalanWalauThrottleBootBaruSaja() {
        // Skenario issue #33: boot 1 detik lalu, APK baru dipasang. Jendela
        // throttle masih menolak BOOT_COMPLETED, tetapi MY_PACKAGE_REPLACED
        // wajib tetap menjadwalkan alarm backup + bot.
        long terakhirBoot = 60_000;
        long kini = 61_000;
        assertFalse(AlarmReceiver.bolehAlarmJalan(
                kini, terakhirBoot, BootReceiver.THROTTLE_BOOT_MS));
        assertFalse(BootReceiver.perluThrottleBoot(BootReceiver.AKSI_GANTI_PAKET));
    }
}
