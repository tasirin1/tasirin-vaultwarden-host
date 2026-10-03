package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test throttle alarm umum (murni waktu, tanpa Android runtime). */
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
}
