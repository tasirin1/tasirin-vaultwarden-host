package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Audit 1478: memo kecocokan versi dua tahap yang disatukan dari
// MainActivity + SettingsActivity.
public class MemoVersiCocokTest {

    @Test
    public void tahapDasarDanMemoKonsisten() {
        MemoVersiCocok m = new MemoVersiCocok();
        assertTrue(m.cocokTahap1("1.37.5", "v1.37.5"));
        assertTrue(m.cocokTahap1("1.37.5", "v1.37.5"));
        assertFalse(m.cocokTahap1("1.37.5", "1.37.6"));
        assertFalse(m.cocokTahap1("1.37.5", "1.37.6"));
        assertFalse(m.cocokTahap1(null, "1.37.5"));
        assertFalse(m.cocokTahap1(null, "1.37.5"));
    }

    @Test
    public void pendNullTidakNpe() {
        // Audit 2805: pend null dinormalkan "", tak meledak.
        MemoVersiCocok m = new MemoVersiCocok();
        m.cocokTahap1("1.32.0", null);
        m.cocokTahap1("1.32.0", null);
        m.cocokTahap2(null, null);
        m.cocokTahap2(null, null);
    }

    @Test
    public void duaTahapTidakSalingBocor() {
        MemoVersiCocok m = new MemoVersiCocok();
        assertFalse(m.cocokTahap1("1.0.0", "2.0.0"));
        assertTrue(m.cocokTahap2("1.0.0", "1.0.0"));
        assertTrue(m.cocokTahap2("1.0.0", "1.0.0"));
        assertFalse(m.cocokTahap2("1.0.0", "2.0.0"));
        assertFalse(m.cocokTahap1("1.0.0", "2.0.0"));
    }
}
