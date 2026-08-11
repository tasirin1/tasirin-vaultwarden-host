package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit test util murni (tanpa Android runtime). */
public class TgBackupTest {

    @Test
    public void humanBytes_skalaBenar() {
        assertEquals("0 B", TgBackup.humanBytes(0));
        assertEquals("512 B", TgBackup.humanBytes(512));
        assertEquals("1.0 KB", TgBackup.humanBytes(1024));
        assertEquals("1.5 KB", TgBackup.humanBytes(1536));
        assertEquals("2.0 MB", TgBackup.humanBytes(2 * 1048576));
    }
}
