package com.winlator.cmod.components.offline;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class PackageInstaller {
    private static final int BURN_MAGIC = 0x00F14300;
    private static final String[] NESTED_SUFFIXES = {".7z", ".zip", ".cab", ".exe", ".msi", ".rar", ".xz", ".gz", ".tar"};

    /** What laying out a package or wrapper did. */
    public static final class Result {
        public String product = "", version = "", how = "msi";
        public int files, assemblies, registryCount;
        public final List<RegValue> registry = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public final List<String> packages = new ArrayList<>();

        void addNote(String note) {
            if (!notes.contains(note)) notes.add(note);
        }
    }

    private final OfflineTools tools;
    private final String user;

    public PackageInstaller(OfflineTools tools, String user) {
        this.tools = tools; this.user = user;
    }

    /** Lays [source] (.msi or wrapper .exe) out under [out]; [work] is scratch space beside it. */
    public Result install(File source, File out, File work, OfflineTools.Progress progress) throws IOException {
        if (!out.isDirectory() && !out.mkdirs()) throw new IOException("could not make " + out);
        if (OfflineTools.kindOf(source).equals("msi")) {
            Result r = new Result();
            installPackage(source, out, work, progress, r);
            r.packages.add(source.getName());
            return r;
        }
        return installWrapper(source, out, work, progress);
    }

    private void installPackage(File msi, File out, File work, OfflineTools.Progress span, Result total) throws IOException {
        installPackage(msi, out, work, span, total, 0);
    }

    private void installPackage(File msi, File out, File work, OfflineTools.Progress span, Result total, int depth) throws IOException {
        List<File> nested;
        try (MsiDatabase db = new MsiDatabase(msi)) {
            MsiLayout layout = new MsiLayout(db, out, user, tools, span);
            layout.install(work);
            if (total.product.isEmpty()) {
                total.product = layout.product();
                total.version = layout.version();
            }
            total.files += layout.fileCount;
            total.assemblies += layout.assemblyCount;
            total.registryCount += layout.registryCount;
            total.registry.addAll(layout.registry);
            for (String n : layout.notes) total.addNote(n);
            nested = layout.nestedPackages;
        }
        // A package its own custom action installs with msiexec, from where this one put it.
        for (File p : nested) {
            if (depth >= 2) {
                total.addNote(p.getName() + ": not installed (packages nested too deep)");
                continue;
            }
            try {
                installPackage(p, out, work, (f, text) -> span.report(-1, text), total, depth + 1);
                total.packages.add(p.getName());
            } catch (IOException e) {
                // The package that ran it is in place either way; this one is named in the notes.
                total.addNote(p.getName() + ": not installed: " + e.getMessage());
            }
        }
    }

    private Result installWrapper(File path, File out, File work, OfflineTools.Progress progress) throws IOException {
        Result total = new Result();
        File scratch = new File(work, ".unpack-" + System.nanoTime());
        File dest = new File(scratch, "x");
        if (!dest.mkdirs()) throw new IOException("could not make " + dest);
        try {
            total.how = unpackInstaller(path, dest, progress);
            List<File> packages = choosePackages(packagesIn(dest), dest);
            if (packages.isEmpty()) {
                throw new IOException(path.getName() + " holds no installer package: it is its own setup program, which cannot be run here");
            }
            for (int i = 0; i < packages.size(); i++) {
                File p = packages.get(i);
                double lo = 0.1 + 0.9 * i / packages.size(), hi = 0.1 + 0.9 * (i + 1) / packages.size();
                progress.report(lo, "package " + (i + 1) + " of " + packages.size() + ": " + p.getName());
                Result one = new Result();
                try {
                    installPackage(p, out, scratch, OfflineTools.span(progress, lo, hi), one);
                } catch (MsiLayout.NothingToInstall e) {
                    // A package with nothing for this prefix (a patch for another Windows) is skipped,
                    // unless it is the only one; anything else is the whole installer's failure.
                    if (packages.size() > 1) {
                        total.addNote(p.getName() + ": " + e.getMessage());
                        continue;
                    }
                    throw e;
                }
                total.packages.add(p.getName());
                if (total.product.isEmpty()) {
                    total.product = one.product;
                    total.version = one.version;
                }
                total.files += one.files;
                total.assemblies += one.assemblies;
                total.registryCount += one.registryCount;
                total.registry.addAll(one.registry);
                for (String n : one.notes) total.addNote(n);
            }
        } finally {
            OfflineTools.deleteTree(scratch);
        }
        return total;
    }

    // ---------------------------------------------------------------- opening installers

    /** Opens an installer or archive into [dest]: a bundle by its header, an embedded cabinet, or 7-Zip. */
    public String unpackInstaller(File path, File dest, OfflineTools.Progress progress) throws IOException {
        String kind = OfflineTools.kindOf(path);
        if (kind.equals("exe")) {
            List<long[]> containers = burnContainers(path);
            if (containers != null && !containers.isEmpty()) {
                unpackBurn(path, dest, containers, progress);
                return "bundle";
            }
        }
        if (kind.equals("cab") || kind.equals("exe") && indexOf(path, "MSCF".getBytes(StandardCharsets.US_ASCII), 0) >= 0) {
            if (unpackCabinets(path, dest) && OfflineTools.hasAnyFile(dest)) return "cabinet";
            clear(dest);
        }
        progress.report(0.02, "unpacking " + path.getName());
        if (!tools.extract(path, dest) || !OfflineTools.hasAnyFile(dest)) {
            throw new IOException("could not open " + path.getName() + ": nothing inside it can be unpacked");
        }
        return "archive";
    }

    private static void clear(File dir) {
        File[] all = dir.listFiles();
        if (all != null) for (File f : all) OfflineTools.deleteTree(f);
    }


    private boolean unpackCabinets(File path, File dest) throws IOException {
        boolean any = false;
        try (RandomAccessFile f = new RandomAccessFile(path, "r")) {
            long length = f.length();
            long at = 0;
            byte[] magic = "MSCF".getBytes(StandardCharsets.US_ASCII);
            byte[] head = new byte[36];
            int index = 0;
            while (at < length) {
                long found = indexOf(f, magic, at);
                if (found < 0) break;
                f.seek(found);
                if (f.read(head) < head.length) break;
                long size = CompoundFile.le32(head, 8) & 0xFFFFFFFFL;
                boolean sane = CompoundFile.le32(head, 4) == 0 && CompoundFile.le32(head, 12) == 0
                        && size > 36 && found + size <= length && (CompoundFile.le32(head, 16) & 0xFFFFFFFFL) < size
                        && head[24] == 3 && head[25] == 1;
                if (!sane) {
                    at = found + 4;
                    continue;
                }
                File cab = new File(dest.getParentFile(), "carved" + (index++) + ".cab");
                copyRange(f, found, size, cab);
                boolean ok = tools.extract(cab, dest);
                cab.delete();
                any |= ok;
                at = found + size;
            }
        }
        return any;
    }

    private static void copyRange(RandomAccessFile f, long start, long size, File target) throws IOException {
        try (FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[1 << 16];
            f.seek(start);
            long left = size;
            while (left > 0) {
                int n = f.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) throw new IOException("short read");
                out.write(buf, 0, n);
                left -= n;
            }
        }
    }

    static long indexOf(File path, byte[] pattern, long from) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(path, "r")) {
            return indexOf(f, pattern, from);
        }
    }

    /** The first offset at or after [from] where [pattern] sits, or -1; read in chunks. */
    static long indexOf(RandomAccessFile f, byte[] pattern, long from) throws IOException {
        long length = f.length();
        byte[] buf = new byte[1 << 20];
        long pos = from;
        while (pos < length) {
            f.seek(pos);
            int n = f.read(buf, 0, (int) Math.min(buf.length, length - pos));
            if (n <= 0) return -1;
            outer:
            for (int i = 0; i + pattern.length <= n; i++) {
                for (int j = 0; j < pattern.length; j++) if (buf[i + j] != pattern[j]) continue outer;
                return pos + i;
            }
            if (pos + n >= length) return -1;
            pos += n - (pattern.length - 1);
        }
        return -1;
    }

    /**
     * A WiX bundle's containers as {offset, size}: the .wixburn section of the stub names their
     * sizes; each is a cabinet laid out after the stub, found by its header since signing can move
     * them. null when the file is no bundle.
     */
    static List<long[]> burnContainers(File path) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(path, "r")) {
            byte[] head = new byte[(int) Math.min(f.length(), 1 << 16)];
            f.readFully(head);
            int pe = CompoundFile.le32(head, 0x3C);
            if (pe < 0 || pe + 24 > head.length || head[pe] != 'P' || head[pe + 1] != 'E' || head[pe + 2] != 0 || head[pe + 3] != 0) return null;
            int count = CompoundFile.le16(head, pe + 6), optional = CompoundFile.le16(head, pe + 20);
            int table = pe + 24 + optional;
            long secOffset = -1, secSize = 0;
            for (int i = 0; i < count && table + 40 * i + 40 <= head.length; i++) {
                int at = table + 40 * i;
                String name = new String(head, at, 8, StandardCharsets.US_ASCII).replace("\0", "");
                if (name.equals(".wixburn")) {
                    secSize = CompoundFile.le32(head, at + 16) & 0xFFFFFFFFL;
                    secOffset = CompoundFile.le32(head, at + 20) & 0xFFFFFFFFL;
                }
            }
            if (secOffset < 0 || secSize < 52) return null;
            byte[] sec = new byte[(int) Math.min(secSize, 4096)];
            f.seek(secOffset);
            f.readFully(sec);
            if (CompoundFile.le32(sec, 0) != BURN_MAGIC) return null;
            long stub = CompoundFile.le32(sec, 24) & 0xFFFFFFFFL;
            int containers = CompoundFile.le32(sec, 44);
            if (containers < 0 || 48 + 4L * containers > sec.length) return null;
            List<long[]> found = new ArrayList<>();
            long position = stub;
            byte[] magic = "MSCF".getBytes(StandardCharsets.US_ASCII);
            byte[] cab = new byte[12];
            for (int i = 0; i < containers; i++) {
                long size = CompoundFile.le32(sec, 48 + 4 * i) & 0xFFFFFFFFL;
                long start;
                while (true) {
                    start = indexOf(f, magic, position);
                    if (start < 0 || start + 12 > f.length()) return null;
                    f.seek(start);
                    f.readFully(cab);
                    if ((CompoundFile.le32(cab, 8) & 0xFFFFFFFFL) == size) break;
                    position = start + 4;
                }
                found.add(new long[]{start, size});
                position = start + size;
            }
            return found;
        }
    }

    private static final Pattern PAYLOAD = Pattern.compile("<Payload\\b[^>]*?\\bFilePath=\"([^\"]+)\"[^>]*?\\bSourcePath=\"([^\"]+)\"");

    /** Unpacks a bundle's containers and gives the payloads the names its manifest lists. */
    private void unpackBurn(File path, File dest, List<long[]> containers, OfflineTools.Progress progress) throws IOException {
        Map<String, String> names = new HashMap<>();
        try (RandomAccessFile f = new RandomAccessFile(path, "r")) {
            for (int index = 0; index < containers.size(); index++) {
                progress.report(0.02 + 0.08 * index / Math.max(1, containers.size()),
                        "unpacking the bundle, part " + (index + 1) + " of " + containers.size());
                File cab = new File(dest, "container" + index + ".cab");
                copyRange(f, containers.get(index)[0], containers.get(index)[1], cab);
                File folder = new File(dest, "container" + index);
                if (!folder.mkdirs()) throw new IOException("could not make " + folder);
                boolean ok = tools.extract(cab, folder);
                cab.delete();
                if (!ok) throw new IOException("could not unpack the bundle's part " + (index + 1));
                if (index == 0) {
                    File manifest = new File(folder, "0");
                    if (manifest.isFile()) {
                        Matcher m = PAYLOAD.matcher(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
                        while (m.find()) names.put(m.group(2), m.group(1));
                    }
                    continue;
                }
                File[] entries = folder.listFiles();
                if (entries == null) continue;
                for (File entry : entries) {
                    String wanted = names.get(entry.getName());
                    if (wanted == null) continue;
                    StringBuilder rel = new StringBuilder();
                    for (String part : wanted.replace('\\', '/').split("/")) {
                        if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
                        if (rel.length() > 0) rel.append('/');
                        rel.append(part);
                    }
                    if (rel.length() == 0) continue;
                    File target = new File(folder, rel.toString());
                    File parent = target.getParentFile();
                    if (parent != null) parent.mkdirs();
                    Files.move(entry.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** The Windows Installer packages an unpacked installer holds, shallowest first, by name. */
    private static List<File> packagesIn(File root) {
        List<File> found = new ArrayList<>();
        for (File f : OfflineTools.walkFiles(root)) if (OfflineTools.kindOf(f).equals("msi")) found.add(f);
        String base = root.getPath();
        found.sort((a, b) -> {
            int da = depth(base, a), db = depth(base, b);
            if (da != db) return Integer.compare(da, db);
            return a.getName().toLowerCase(Locale.ROOT).compareTo(b.getName().toLowerCase(Locale.ROOT));
        });
        return found;
    }

    private static int depth(String base, File f) {
        String rel = f.getPath().substring(base.length() + 1);
        return rel.split(Pattern.quote(File.separator)).length;
    }

    /**
     * Which packages make up the install: the ones at the top of the installer (a bundle keeps each
     * in its own folder, so there every level counts), for the x86 and x64 platforms. The .NET
     * Framework 4.5+ installers carry a complete product (netfx_Full_*) beside the 4.0 parts they
     * patch (netfx_Core_*, netfx_Extended_*) and per-Windows copies in NetFx* folders: the complete
     * product wins, the copies are left out.
     */
    private static List<File> choosePackages(List<File> found, File root) {
        List<File> chosen = new ArrayList<>();
        if (found.isEmpty()) return chosen;
        String base = root.getPath();
        int min = Integer.MAX_VALUE;
        for (File f : found) min = Math.min(min, depth(base, f));
        boolean bundle = new File(root, "container1").isDirectory();
        List<File> top = new ArrayList<>();
        for (File f : found) if (bundle || depth(base, f) == min) top.add(f);
        boolean full = false, parts = false;
        for (File f : top) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.contains("full")) full = true;
            if (n.contains("_core") || n.contains("_extended")) parts = true;
        }
        if (full && parts) {
            List<File> kept = new ArrayList<>();
            for (File f : top) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (!(n.contains("_core") || n.contains("_extended"))) kept.add(f);
            }
            top = kept;
        }
        for (File f : top) {
            String platform;
            try (MsiDatabase db = new MsiDatabase(f)) {
                platform = MsiLayout.platformOf(db);
            } catch (IOException e) {
                continue;
            }
            if (platform.equals("arm64") || platform.equals("arm")) continue;
            chosen.add(f);
        }
        return chosen;
    }


    public void unpackForSteps(File path, File dest, OfflineTools.Progress progress) throws IOException {
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("could not make " + dest);
        unpackInstaller(path, dest, progress);
        progress.report(-1, "opening archives inside " + path.getName());
        openNested(dest, 0);
    }

    // ---------------------------------------------------------------- recordings

    /**
     * Opens [path] for a component recording to take files out of: the same layout the recorder
     * (winlator-contents tools/snapshot/record.py) maps its file sources to - 7-Zip's extraction,
     * then each archive inside opened in place as "<name>.nested", two levels deep. With
     * [innoFirst], innoextract is tried first, as the recorder did.
     */
    public void unpackOnly(File path, File dest, OfflineTools inno, OfflineTools.Progress progress) throws IOException {
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("could not make " + dest);
        if (inno != null) {
            progress.report(-1, "unpacking " + path.getName());
            if (!inno.extract(path, dest) || !OfflineTools.hasAnyFile(dest)) clear(dest);
        }
        if (!OfflineTools.hasAnyFile(dest)) {
            progress.report(-1, "unpacking " + path.getName());
            tools.extract(path, dest);
        }
        if (!OfflineTools.hasAnyFile(dest)) throw new IOException("could not open " + path.getName());
        progress.report(-1, "opening archives inside " + path.getName());
        openNested(dest, 0);
    }

    private int openNested(File dest, int depth) {
        if (depth > 1) return 0;
        int opened = 0;
        List<File> candidates = new ArrayList<>();
        for (File f : OfflineTools.walkFiles(dest)) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            String suffix = n.lastIndexOf('.') >= 0 ? n.substring(n.lastIndexOf('.')) : "";
            for (String s : NESTED_SUFFIXES) if (s.equals(suffix)) candidates.add(f);
        }
        for (File path : candidates) {
            if (path.getName().endsWith(".nested") || path.length() < 1024) continue;
            if (!tools.canOpen(path)) continue;
            File folder = new File(path.getParentFile(), path.getName() + ".nested");
            folder.mkdirs();
            tools.extract(path, folder);
            if (OfflineTools.hasAnyFile(folder)) opened += 1 + openNested(folder, depth + 1);
            else OfflineTools.deleteTree(folder);
        }
        return opened;
    }

    /** The names a set of values would make, deduplicated, for tests. */
    static Set<String> keys(List<RegValue> values) {
        Set<String> out = new LinkedHashSet<>();
        for (RegValue v : values) out.add(v.hive + "\\" + v.key);
        return out;
    }
}
