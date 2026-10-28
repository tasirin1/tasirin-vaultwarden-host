package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AboutDialogTest {

    @Test
    public void rangkaianVersiGabungYangAdaSaja() {
        assertEquals("1.2.3", AboutDialog.rangkaianVersi("1.2.3", null, null));
        assertEquals("1.2.3 · binary v2024.1.0",
                AboutDialog.rangkaianVersi("1.2.3", "2024.1.0", null));
        assertEquals("1.2.3 · vault v5.1.0",
                AboutDialog.rangkaianVersi("1.2.3", null, "5.1.0"));
        assertEquals("1.2.3 · binary v2024.1.0 · vault v5.1.0",
                AboutDialog.rangkaianVersi("1.2.3", "2024.1.0", "5.1.0"));
        assertEquals("?", AboutDialog.rangkaianVersi(null, null, null));
        assertEquals("?", AboutDialog.rangkaianVersi("", null, null));
    }
}
