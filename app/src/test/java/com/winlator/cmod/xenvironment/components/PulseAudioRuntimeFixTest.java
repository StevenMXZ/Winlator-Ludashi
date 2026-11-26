package com.winlator.cmod.xenvironment.components;

import com.winlator.cmod.contents.ElfLibrary;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

import static org.junit.Assert.*;

public class PulseAudioRuntimeFixTest {
    @Test public void removesOnlyUnusedLameAndRejectsUnknownBuild() throws Exception {
        File library = File.createTempFile("pulse-sndfile", ".so");
        File archive = new File("src/main/assets/pulseaudio.tzst");
        try {
            boolean found = false;
            try (TarArchiveInputStream tar = new TarArchiveInputStream(
                    new ZstdCompressorInputStream(Files.newInputStream(archive.toPath())))) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextTarEntry()) != null) {
                    if ("libsndfile.so".equals(entry.getName())) {
                        Files.copy(tar, library.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        found = true;
                        break;
                    }
                }
            }
            assertTrue(found);
            assertTrue(ElfLibrary.dependencies(library).contains("libmp3lame.so"));
            PulseAudioRuntimeFix.removeUnusedLame(library);
            assertEquals(Arrays.asList("libm.so", "libdl.so", "libc.so"), ElfLibrary.dependencies(library));
            byte[] patched = Files.readAllBytes(library.toPath());
            PulseAudioRuntimeFix.removeUnusedLame(library);
            assertArrayEquals(patched, Files.readAllBytes(library.toPath()));
            patched[0] ^= 1;
            Files.write(library.toPath(), patched);
            try {
                PulseAudioRuntimeFix.removeUnusedLame(library);
                fail("Unexpected ELF must not be patched");
            } catch (IOException expected) {
                assertArrayEquals(patched, Files.readAllBytes(library.toPath()));
            }
        } finally {
            Files.deleteIfExists(library.toPath());
        }
    }
}
