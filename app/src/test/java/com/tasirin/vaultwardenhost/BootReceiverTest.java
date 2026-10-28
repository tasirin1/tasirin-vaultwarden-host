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
    public void throttleBootMenahan_duplikatBootSamaDitahan() {
        assertTrue(BootReceiver.throttleBootMenahan(
                70_000, 45_000, 1_025_000, 1_000_000, BootReceiver.THROTTLE_BOOT_MS));
    }

    @Test
    public void throttleBootMenahan_bootBerbedaDilepasWalauElapsedDekat() {
        assertFalse(BootReceiver.throttleBootMenahan(
                70_000, 45_000, 1_000_000 + 259_200_000L, 1_000_000,
                BootReceiver.THROTTLE_BOOT_MS));
        assertFalse(BootReceiver.throttleBootMenahan(
                70_000, 45_000, 1_000_000 + 7_200_000L, 1_000_000,
                BootReceiver.THROTTLE_BOOT_MS));
    }

    @Test
    public void throttleBootMenahan_kasusBatasLainDilepas() {
        assertFalse(BootReceiver.throttleBootMenahan(
                70_000, 0, 1_025_000, 0, BootReceiver.THROTTLE_BOOT_MS));
        assertFalse(BootReceiver.throttleBootMenahan(
                30_000, 45_000, 1_025_000, 1_000_000, BootReceiver.THROTTLE_BOOT_MS));
        assertFalse(BootReceiver.throttleBootMenahan(
                120_000, 45_000, 1_075_000, 1_000_000, BootReceiver.THROTTLE_BOOT_MS));
        assertFalse(BootReceiver.throttleBootMenahan(
                70_000, 45_000, 900_000, 1_000_000, BootReceiver.THROTTLE_BOOT_MS));
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
