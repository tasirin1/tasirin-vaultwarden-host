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
    public void kunciBerbungkusZipTetapDitolak() {
        assertFalse(FileShareProvider.namaBolehDibagikan("key.pem.zip"));
        assertFalse(FileShareProvider.namaBolehDibagikan("ca-key.pem.enc"));
        assertFalse(FileShareProvider.namaBolehDibagikan("server.key.zip"));
        assertTrue(FileShareProvider.namaBolehDibagikan("monkey.zip"));
    }

    @Test
    public void namaBiasaBerisiKeyTakIkutDitolak() {
        assertTrue(FileShareProvider.namaBolehDibagikan("monkey.zip"));
        assertTrue(FileShareProvider.namaBolehDibagikan("monkey.pem"));
        assertFalse(FileShareProvider.namaBolehDibagikan("ca-key.pem"));
        assertFalse(FileShareProvider.namaBolehDibagikan("key.pem"));
    }

    @Test
    public void namaBiasaBerisiCaKeyTakIkutDitolak() {
        assertTrue(FileShareProvider.namaBolehDibagikan("backup-ca-key-info.txt"));
        assertTrue(FileShareProvider.namaBolehDibagikan("catatan-ca-key.txt"));
        assertFalse(FileShareProvider.namaBolehDibagikan("ca-key.pem"));
        assertFalse(FileShareProvider.namaBolehDibagikan("ca-key.pem.zip"));
    }

    @org.junit.Test
    public void tipeMime_sesuaiEkstensi() {
        assertEquals("application/json", com.tasirin.vaultwardenhost.FileShareProvider.tipeMime(
                android.net.Uri.parse("content://x/app-config.json")));
        assertEquals("text/plain", com.tasirin.vaultwardenhost.FileShareProvider.tipeMime(
                android.net.Uri.parse("content://x/catatan.txt")));
        assertEquals("application/zip", com.tasirin.vaultwardenhost.FileShareProvider.tipeMime(
                android.net.Uri.parse("content://x/backup.zip")));
        assertEquals("application/x-pem-file", com.tasirin.vaultwardenhost.FileShareProvider.tipeMime(
                android.net.Uri.parse("content://x/ca.pem")));
        assertEquals("application/octet-stream", com.tasirin.vaultwardenhost.FileShareProvider.tipeMime(
                android.net.Uri.parse("content://x/blob-takdikenal")));
    }

    @org.junit.Test
    public void modeBacaSaja_tolakTulis() {
        assertTrue(com.tasirin.vaultwardenhost.FileShareProvider.modeBacaSaja(null));
        assertTrue(com.tasirin.vaultwardenhost.FileShareProvider.modeBacaSaja("r"));
        assertTrue(com.tasirin.vaultwardenhost.FileShareProvider.modeBacaSaja("rt"));
        assertFalse(com.tasirin.vaultwardenhost.FileShareProvider.modeBacaSaja("w"));
        assertFalse(com.tasirin.vaultwardenhost.FileShareProvider.modeBacaSaja("rw"));
    }

    @Test
    public void cacheHanyaBerbagiApp() {
        assertTrue(FileShareProvider.namaCacheBolehDibagikan("ca.pem"));
        assertTrue(FileShareProvider.namaCacheBolehDibagikan("cert.pem"));
        assertTrue(FileShareProvider.namaCacheBolehDibagikan("ca-cadangan-20240101-ab12.pem"));
        assertTrue(FileShareProvider.namaCacheBolehDibagikan("app-config-20240101-ab12.json"));
        assertTrue(FileShareProvider.namaCacheBolehDibagikan("app-config-20240101-ab12.json.enc"));
        assertFalse(FileShareProvider.namaCacheBolehDibagikan("monkey.zip"));
        assertFalse(FileShareProvider.namaCacheBolehDibagikan("backup.zip"));
        assertFalse(FileShareProvider.namaCacheBolehDibagikan("key.pem"));
        assertFalse(FileShareProvider.namaCacheBolehDibagikan("vwtg-restore.zip"));
        assertFalse(FileShareProvider.namaCacheBolehDibagikan(null));
    }
}
