package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Unit test fungsi murni — tanpa Android runtime (jalan di CI via JVM). */
public class UpdaterTest {

    @Test
    public void normVersion_hapusHurufV() {
        assertEquals("1.37.1", Updater.normVersion("v1.37.1"));
        assertEquals("1.37.1", Updater.normVersion("1.37.1"));
        assertEquals("", Updater.normVersion("v"));
    }

    @Test
    public void normVersion_nullTetapNull() {
        assertNull(Updater.normVersion(null));
    }

    @Test
    public void extractTag_ambilTagNameDariJson() {
        String body = "{\"tag_name\":\"v1.37.1\",\"name\":\"1.37.1\"}";
        assertEquals("v1.37.1", Updater.extractTag(body));
    }

    @Test
    public void parseBinaryVersion_ambilXyzDariOutputVersion() {
        assertEquals("1.37.3", Updater.parseBinaryVersion("vaultwarden 1.37.3"));
        assertEquals("1.37.3", Updater.parseBinaryVersion("1.37.3"));
        assertEquals("1.37.3", Updater.parseBinaryVersion("vaultwarden 1.37.3 (abc123)"));
        assertNull(Updater.parseBinaryVersion("?"));
        assertNull(Updater.parseBinaryVersion(""));
        assertNull(Updater.parseBinaryVersion(null));
    }

    @Test
    public void extractTag_tanpaTagNameMengembalikanNull() {
        assertNull(Updater.extractTag("{\"name\":\"x\"}"));
        assertNull(Updater.extractTag(""));
        assertNull(Updater.extractTag(null));
    }
}
