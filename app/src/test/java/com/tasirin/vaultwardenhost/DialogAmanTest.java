package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertNull;

import org.junit.Test;

public class DialogAmanTest {

    @Test
    public void tampilDanTutupAmanUntukNull() {
        assertNull(DialogAman.tampil(null, null));
        DialogAman.tutup(null);
    }
}
