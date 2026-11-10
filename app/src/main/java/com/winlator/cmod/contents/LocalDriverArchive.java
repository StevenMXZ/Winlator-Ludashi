package com.winlator.cmod.contents;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/** Format detection and safe extraction paths for locally imported AdrenoTools drivers. */
public final class LocalDriverArchive {
    private LocalDriverArchive() {}

    /** The content URI's name and MIME type may be unreliable; inspect the ZIP header. */
    public static boolean isZip(InputStream input) throws IOException {
        if (input == null) return false;
        int a = input.read(), b = input.read(), c = input.read(), d = input.read();
        return a == 'P' && b == 'K' &&
                ((c == 3 && d == 4) || (c == 5 && d == 6) || (c == 7 && d == 8));
    }

    /** Blocks absolute paths, Windows-style traversal, and ZIP entries escaping staging. */
    public static File resolveEntry(File staging, String entryName) throws IOException {
        if (entryName == null || entryName.isEmpty()) throw new IOException("Empty driver ZIP entry");
        String name = entryName.replace('\\', '/');
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*"))
            throw new IOException("Absolute path in driver ZIP");
        File root = staging.getCanonicalFile();
        File resolved = new File(root, name).getCanonicalFile();
        if (root.equals(resolved) || !resolved.toPath().startsWith(root.toPath()))
            throw new IOException("Unsafe path in driver ZIP");
        return resolved;
    }
}
