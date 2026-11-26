package com.winlator.cmod.core;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Streaming SHA-256 for versioning packaged components. */
public final class Sha256Util {
    private Sha256Util() {}

    public static byte[] calculate(Context context, String asset) throws IOException {
        try (InputStream input = context.getAssets().open(asset)) {
            return calculate(input);
        }
    }

    public static byte[] calculate(InputStream input) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        byte[] buffer = new byte[32768];
        int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        return digest.digest();
    }

    public static boolean matches(File marker, byte[] expected) {
        if (!marker.isFile() || marker.length() != expected.length) return false;
        try {
            return MessageDigest.isEqual(Files.readAllBytes(marker.toPath()), expected);
        } catch (IOException error) {
            return false;
        }
    }

    public static void write(File marker, byte[] digest) throws IOException {
        Files.write(marker.toPath(), digest);
    }
}
