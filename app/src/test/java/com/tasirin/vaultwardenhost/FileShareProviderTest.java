package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test fungsi murni — tanpa Android runtime (jalan di CI via JVM). */
public class FileShareProviderTest {

    @Test
    public void kunciPrivatDitolak() {
        assertFalse(FileShareProvider.namaBolehDibagikan("key.pem"));
        assertFalse(FileShareProvider.namaBolehDibagikan("ca-key.pem"));
        assertFalse(FileShareProvider.namaBolehDibagikan("KEY.PEM"));
        assertFalse(FileShareProvider.namaBolehDibagikan("server.key"));
    }

    @Test
    public void sertifikatPublikDiterima() {
        assertTrue(FileShareProvider.namaBolehDibagikan("ca.pem"));
        assertTrue(FileShareProvider.namaBolehDibagikan("cert.pem"));
        assertTrue(FileShareProvider.namaBolehDibagikan("backup.zip"));
        assertTrue(FileShareProvider.namaBolehDibagikan("app-config.json"));
    }

    @Test
    public void namaBiasaBerisiKeyTakIkutDitolak() {
        assertTrue(FileShareProvider.namaBolehDibagikan("monkey.zip"));
        assertTrue(FileShareProvider.namaBolehDibagikan("monkey.pem"));
        assertFalse(FileShareProvider.namaBolehDibagikan("ca-key.pem"));
        assertFalse(FileShareProvider.namaBolehDibagikan("key.pem"));
    }
}
