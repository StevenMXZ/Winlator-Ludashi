package com.winlator.cmod.xenvironment.components;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Compatibility fix for the exact upstream PulseAudio 17 libsndfile build. */
final class PulseAudioRuntimeFix {
    private static final String ORIGINAL = "c7eab3b35b58667793145cc26f68eda792ff54c4c5390832573e3c3ed0343269";
    private static final String PATCHED = "ef6345306c0e903fbb85ef9a043b8c2718473343760968429060a13f564110fa";
    private static final int LAME_ENTRY = 0x6f190;
    private static final int DYNAMIC_END = 0x6f340;
    private static final int ENTRY_SIZE = 16;

    private PulseAudioRuntimeFix() {}

    static void removeUnusedLame(File library) throws IOException {
        byte[] bytes = Files.readAllBytes(library.toPath());
        String digest = sha256(bytes);
        if (PATCHED.equals(digest)) return;
        if (!ORIGINAL.equals(digest))
            throw new IOException("Unexpected PulseAudio libsndfile build; review LAME dependency");
        // The inspected ELF imports no LAME symbols and has no LAME version requirement.
        // Compact the dynamic table over DT_NEEDED(libmp3lame.so), including DT_NULL.
        System.arraycopy(bytes, LAME_ENTRY + ENTRY_SIZE, bytes, LAME_ENTRY,
                DYNAMIC_END - LAME_ENTRY);
        if (!PATCHED.equals(sha256(bytes))) throw new IOException("Invalid PulseAudio ELF patch");
        Files.write(library.toPath(), bytes);
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder(64);
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                int n = value & 0xff;
                hex.append(Character.forDigit(n >>> 4, 16)).append(Character.forDigit(n & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
