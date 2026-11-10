package com.winlator.cmod.contents;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.Collections;

public class ElfLibraryTest {
    private byte[] library() {
        ByteBuffer b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, 0x464c457f); b.put(4, (byte)2); b.put(5, (byte)1);
        b.putShort(16, (short)3); b.putShort(18, (short)183);
        b.putLong(32, 64); b.putShort(54, (short)56); b.putShort(56, (short)2);
        b.putInt(64, 1); b.putLong(64+32, 512);
        b.putInt(120, 2); b.putLong(120+8, 200); b.putLong(120+32, 48);
        b.putLong(200, 1); b.putLong(208, 0);
        b.putLong(216, 5); b.putLong(224, 300);
        b.position(300); b.put("libgallium-test.so\0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return b.array();
    }
    private File write(byte[] data) throws IOException {
        File f=File.createTempFile("driver", ".so");
        Files.write(f.toPath(),data); return f;
    }
    @Test public void readsPairedGalliumFromDynamicTable() throws Exception {
        File f=write(library());
        try { assertEquals(Collections.singletonList("libgallium-test.so"),ElfLibrary.dependencies(f)); }
        finally { f.delete(); }
    }
    @Test public void rejectsWrongArchitectureAndTruncatedHeaders() throws Exception {
        byte[] wrong=library(); wrong[18]=62;
        for(byte[] data:new byte[][]{wrong, new byte[]{0x7f,69,76,70}, java.util.Arrays.copyOf(library(),90)}) {
            File f=write(data);
            try { ElfLibrary.dependencies(f); fail("Invalid ELF accepted"); }
            catch(IOException expected) {} finally { f.delete(); }
        }
    }
}
