package com.winlator.cmod.components.offline;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * What the offline engine needs from outside: an archive tool (the app's bundled 7-Zip on the
 * device) and somewhere to report progress. Plus the file helpers the engine shares.
 */
public abstract class OfflineTools {
    /** Progress for one piece of work: [fraction] 0..1 (or -1 when unknown) and what it is doing. */
    public interface Progress {
        void report(double fraction, String text);
    }

    /** Opens [archive] (a cabinet, a 7-Zip or self-extracting archive, a package) into [dest]. */
    public abstract boolean extract(File archive, File dest);

    /** True when the archive tool can see anything inside [archive]. */
    public abstract boolean canOpen(File archive);

    /** The slice [lo]..[hi] of [parent]'s progress. */
    public static Progress span(Progress parent, double lo, double hi) {
        return (f, text) -> parent.report(f < 0 ? -1 : lo + (hi - lo) * Math.max(0.0, Math.min(1.0, f)), text);
    }

    /** A file next to [path] called [name], whatever its case; null when there is none. */
    public static File sibling(File path, String name) {
        File folder = path.getAbsoluteFile().getParentFile();
        if (folder == null) return null;
        File candidate = new File(folder, name);
        if (candidate.isFile()) return candidate;
        File[] all = folder.listFiles();
        if (all == null) return null;
        for (File f : all) if (f.isFile() && f.getName().equalsIgnoreCase(name)) return f;
        return null;
    }

    /** Every regular file under [root] (symbolic links left alone), sorted by path. */
    public static List<File> walkFiles(File root) {
        List<File> out = new ArrayList<>();
        Deque<File> todo = new ArrayDeque<>();
        todo.push(root);
        while (!todo.isEmpty()) {
            File dir = todo.pop();
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File c : children) {
                if (Files.isSymbolicLink(c.toPath())) continue;
                if (c.isDirectory()) todo.push(c);
                else if (Files.isRegularFile(c.toPath(), LinkOption.NOFOLLOW_LINKS)) out.add(c);
            }
        }
        Collections.sort(out);
        return out;
    }

    public static boolean hasAnyFile(File root) {
        File[] children = root.listFiles();
        if (children == null) return false;
        for (File c : children) if (c.isFile() || c.isDirectory() && hasAnyFile(c)) return true;
        return false;
    }

    /** Deletes [root] and everything under it, never following a symbolic link out of it. */
    public static void deleteTree(File root) {
        if (root == null) return;
        try {
            if (Files.isSymbolicLink(root.toPath())) {
                Files.deleteIfExists(root.toPath());
                return;
            }
        } catch (IOException ignored) {
        }
        File[] children = root.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        root.delete();
    }

    /** What a file is by its first bytes: msi, exe, cab or other. */
    public static String kindOf(File f) {
        byte[] head = new byte[8];
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            int n = in.read(head);
            if (n >= 8 && CompoundFile.le64(head, 0) == 0xE11AB1A1E011CFD0L) return "msi";
            if (n >= 2 && head[0] == 'M' && head[1] == 'Z') return "exe";
            if (n >= 4 && head[0] == 'M' && head[1] == 'S' && head[2] == 'C' && head[3] == 'F') return "cab";
        } catch (IOException ignored) {
        }
        return "other";
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
