package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BootReceiverTest {

    @Test
    public void onReceive_throttleHanyaBootBukanGantiPaket() {
        assertTrue(BootReceiver.perluThrottleBoot("android.intent.action.BOOT_COMPLETED"));
        assertFalse(BootReceiver.perluThrottleBoot("android.intent.action.MY_PACKAGE_REPLACED"));
        assertFalse(BootReceiver.perluThrottleBoot(BootReceiver.AKSI_GANTI_PAKET));

        assertTrue(BootReceiver.perluThrottleBoot(null));
    }

    @Test
    public void gantiPaket_tetapJalanWalauThrottleBootBaruSaja() {

        long terakhirBoot = 60_000;
        long kini = 61_000;
        assertFalse(AlarmReceiver.bolehAlarmJalan(
                kini, terakhirBoot, BootReceiver.THROTTLE_BOOT_MS));
        assertFalse(BootReceiver.perluThrottleBoot(BootReceiver.AKSI_GANTI_PAKET));
    }
}
