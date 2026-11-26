package com.winlator.cmod.core;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

import static org.junit.Assert.*;

public class Sha256UtilTest {
    @Test public void hashesWholeStreamAndDetectsStaleMarkers() throws Exception {
        byte[] bytes = new byte[70000];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(bytes);
        assertArrayEquals(expected, Sha256Util.calculate(new ByteArrayInputStream(bytes)));
        File marker = File.createTempFile("sha256", ".sum");
        try {
            assertFalse(Sha256Util.matches(marker, expected));
            Sha256Util.write(marker, expected);
            assertTrue(Sha256Util.matches(marker, expected));
            byte[] different = Sha256Util.calculate(new ByteArrayInputStream("other".getBytes(StandardCharsets.UTF_8)));
            assertFalse(Sha256Util.matches(marker, different));
            Files.write(marker.toPath(), new byte[1]);
            assertFalse(Sha256Util.matches(marker, expected));
        } finally {
            Files.deleteIfExists(marker.toPath());
        }
    }
}
