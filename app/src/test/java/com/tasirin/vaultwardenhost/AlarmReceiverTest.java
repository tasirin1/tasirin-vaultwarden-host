package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AlarmReceiverTest {

    @Test
    public void alarmPertamaSelaluJalan() {
        assertTrue(AlarmReceiver.bolehAlarmJalan(1000, 0, 60_000));
    }

    @Test
    public void rebootResetElapsedDianggapBoleh() {
        assertTrue(AlarmReceiver.bolehAlarmJalan(500, 9000, 60_000));
    }

    @Test
    public void spamDalamJedaDitolak() {
        assertFalse(AlarmReceiver.bolehAlarmJalan(61_000, 60_000, 60_000));
        assertFalse(AlarmReceiver.bolehAlarmJalan(119_999, 60_000, 60_000));
    }

    @Test
    public void tepatJedaBolehLagi() {
        assertTrue(AlarmReceiver.bolehAlarmJalan(120_000, 60_000, 60_000));
    }

    @Test
    public void stempelThrottleSatuBoot_konsistensiJam() {
        long capE = 30_000;
        long capW = 1_700_000_000_000L;
        assertTrue(AlarmReceiver.stempelThrottleSatuBoot(
                capE, capW, capE + 45_000, capW + 45_000));
        assertFalse(AlarmReceiver.stempelThrottleSatuBoot(
                capE, capW, 45_000,
                capW + 3L * 24 * 3600_000L));
        assertFalse(AlarmReceiver.stempelThrottleSatuBoot(
                capE, capW, capE + 45_000,
                capW + 45_000 + 3600_000L));
        assertFalse(AlarmReceiver.stempelThrottleSatuBoot(
                capE, 0, capE + 45_000, capW + 45_000));
        assertFalse(AlarmReceiver.stempelThrottleSatuBoot(
                0, capW, capE + 45_000, capW + 45_000));
        assertTrue(AlarmReceiver.stempelThrottleSatuBoot(
                capE, capW, capE + 45_000,
                capW + 45_000 + 5_000));
        assertFalse(AlarmReceiver.stempelThrottleSatuBoot(
                capE, capW, capE + 45_000,
                capW + 45_000 + 60_000));
        assertFalse(AlarmReceiver.stempelThrottleSatuBoot(
                20_000, capW, 70_000, capW + 80_000));
    }

    @Test
    public void throttleTersimpanMenutupResetProses() {
        assertFalse(AlarmReceiver.bolehAlarmJalan(61_000,
                AlarmReceiver.throttleEfektif(0, 60_000), 60_000));
        assertTrue(AlarmReceiver.bolehAlarmJalan(120_000,
                AlarmReceiver.throttleEfektif(0, 60_000), 60_000));
    }
}
