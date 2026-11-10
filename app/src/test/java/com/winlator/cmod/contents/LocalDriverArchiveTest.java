package com.winlator.cmod.contents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class LocalDriverArchiveTest {
    private static ByteArrayInputStream bytes(int... values) {
        byte[] data = new byte[values.length];
        for (int i = 0; i < values.length; ++i) data[i] = (byte) values[i];
        return new ByteArrayInputStream(data);
    }

    @Test public void recognizesZipWithoutDependingOnFileExtension() throws Exception {
        assertTrue(LocalDriverArchive.isZip(bytes(0x50, 0x4b, 0x03, 0x04, 0)));
        assertTrue(LocalDriverArchive.isZip(bytes(0x50, 0x4b, 0x05, 0x06)));
        assertFalse(LocalDriverArchive.isZip(bytes(0x28, 0xb5, 0x2f, 0xfd)));
        assertFalse(LocalDriverArchive.isZip(bytes(0xfd, 0x37, 0x7a, 0x58, 0x5a)));
        assertFalse(LocalDriverArchive.isZip(bytes(0x50, 0x4b)));
    }

    @Test public void nestedDriverFilesStayUnderStaging() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "ludashi-driver-test");
        assertEquals(new File(root, "meta.json").getCanonicalFile(),
                LocalDriverArchive.resolveEntry(root, "meta.json"));
        assertEquals(new File(root, "lib/vulkan.adreno.so").getCanonicalFile(),
                LocalDriverArchive.resolveEntry(root, "lib\\vulkan.adreno.so"));
    }

    @Test public void rejectsTraversalAndAbsolutePaths() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "ludashi-driver-test");
        for (String entry : new String[]{"../outside.so", "lib/../../outside.so",
                "..\\outside.so", "/tmp/outside.so", "C:\\outside.so", ".", ""}) {
            try {
                LocalDriverArchive.resolveEntry(root, entry);
                fail("Unsafe driver ZIP entry accepted: " + entry);
            } catch (IOException expected) {
                // Rejected without writing outside the import directory.
            }
        }
    }
}
