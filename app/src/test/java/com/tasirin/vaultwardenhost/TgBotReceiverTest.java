package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TgBotReceiverTest {

    @Test
    public void konfigBotSiapHanyaBilaTokenDanChatTerisi() {
        assertTrue(TgBotReceiver.konfigBotSiap("123:abc", "-100123"));
        assertTrue(TgBotReceiver.konfigBotSiap(" 123:abc ", " -100123 "));
        assertFalse(TgBotReceiver.konfigBotSiap("", "-100123"));
        assertFalse(TgBotReceiver.konfigBotSiap("123:abc", ""));
        assertFalse(TgBotReceiver.konfigBotSiap("   ", "   "));
        assertFalse(TgBotReceiver.konfigBotSiap(null, null));
        assertFalse(TgBotReceiver.konfigBotSiap(null, "-100123"));
        assertFalse(TgBotReceiver.konfigBotSiap("123:abc", null));
    }
}
