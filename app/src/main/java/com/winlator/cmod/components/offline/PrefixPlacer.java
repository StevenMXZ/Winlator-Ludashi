package com.winlator.cmod.components.offline;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Puts a laid-out component (its drive_c tree) into a container's Wine prefix. Windows names
 * ignore case and the Android filesystem does not, so each folder and file is matched to the one
 * the prefix already has whatever its case ("Program Files (x86)", "system32"). A file is written
 * beside its target and renamed over it: over one of Wine's builtin DLLs that is a symbolic link
 * into the Wine install, this swaps the link for our file instead of writing through it.
 */
public final class PrefixPlacer {
    private PrefixPlacer() {
    }

    /** Copies every file under [tree] into the same place under [prefix]; returns how many changed. */
    public static int place(File tree, File prefix) throws IOException {
        int placed = 0;
        String base = tree.getPath() + File.separator;
        for (File src : OfflineTools.walkFiles(tree)) {
            String rel = src.getPath().substring(base.length()).replace(File.separatorChar, '/');
            if (placeOne(src, prefix, rel)) placed++;
        }
        return placed;
    }

    /** Where [rel] (forward slashes) lands under [prefix]: its folders and the file as the prefix already spells them. */
    public static File resolve(File prefix, String rel) throws IOException {
        String[] parts = rel.split("/");
        File folder = prefix;
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].isEmpty() || parts[i].equals(".") || parts[i].equals("..")) throw new IOException("bad path " + rel);
            folder = spelled(folder, parts[i]);
        }
        String name = parts[parts.length - 1];
        if (name.isEmpty() || name.equals("..")) throw new IOException("bad path " + rel);
        File target = existing(folder, name);
        return target != null ? target : new File(folder, name);
    }

    /** Puts [src] at [rel] (forward slashes) under [prefix]; false when an identical file is already there. */
    public static boolean placeOne(File src, File prefix, String rel) throws IOException {
        File target = resolve(prefix, rel);
        File folder = target.getParentFile();
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("could not make " + folder);
        if (!Files.isSymbolicLink(target.toPath()) && target.isFile() && target.length() == src.length() && sameBytes(src, target)) {
            return false;
        }
        File temp = new File(folder, "." + target.getName() + ".ludashi");
        Files.copy(src.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    /**
     * Deletes the files under [old] (a folder of the prefix) that [fresh] (the same folder, laid out
     * anew) does not have, whatever their case, and the folders that leaves empty. [before] gets each
     * file just before it goes. Returns how many went.
     */
    public static int removeStale(File fresh, File old, Consumer<File> before) {
        if (!fresh.isDirectory() || !old.isDirectory()) return 0;
        Set<String> keep = new HashSet<>();
        int base = fresh.getPath().length() + 1;
        for (File f : OfflineTools.walkFiles(fresh)) keep.add(f.getPath().substring(base).toLowerCase(Locale.ROOT));
        base = old.getPath().length() + 1;
        Set<File> emptied = new HashSet<>();
        int removed = 0;
        for (File f : OfflineTools.walkFiles(old)) {
            if (keep.contains(f.getPath().substring(base).toLowerCase(Locale.ROOT))) continue;
            before.accept(f);
            if (f.delete()) {
                removed++;
                emptied.add(f.getParentFile());
            }
        }
        List<File> folders = new ArrayList<>(emptied);
        folders.sort((a, b) -> Integer.compare(b.getPath().length(), a.getPath().length()));
        for (File d : folders) {
            while (d != null && !d.equals(old) && d.delete()) d = d.getParentFile();
        }
        return removed;
    }

    /** [parent]/[name] as the prefix spells it when a folder by that name exists in any case. */
    static File spelled(File parent, String name) {
        File exact = new File(parent, name);
        if (exact.isDirectory()) return exact;
        File[] all = parent.listFiles();
        if (all != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (File f : all) if (f.isDirectory() && f.getName().toLowerCase(Locale.ROOT).equals(lower)) return f;
        }
        return exact;
    }

    /** The file called [name] in [folder] whatever its case (a dangling Wine symlink counts), or null. */
    static File existing(File folder, String name) {
        File exact = new File(folder, name);
        if (exact.exists() || Files.isSymbolicLink(exact.toPath())) return exact;
        File[] all = folder.listFiles();
        if (all == null) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        for (File f : all) if (f.getName().toLowerCase(Locale.ROOT).equals(lower) && !f.isDirectory()) return f;
        return null;
    }

    private static boolean sameBytes(File a, File b) {
        try (java.io.InputStream x = new java.io.BufferedInputStream(new java.io.FileInputStream(a));
             java.io.InputStream y = new java.io.BufferedInputStream(new java.io.FileInputStream(b))) {
            byte[] p = new byte[1 << 16], q = new byte[1 << 16];
            while (true) {
                int n = x.read(p);
                if (n < 0) return y.read() < 0;
                int m = 0;
                while (m < n) {
                    int r = y.read(q, m, n - m);
                    if (r < 0) return false;
                    m += r;
                }
                for (int i = 0; i < n; i++) if (p[i] != q[i]) return false;
            }
        } catch (IOException e) {
            return false;
        }
    }
}
