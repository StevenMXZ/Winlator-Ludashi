package com.winlator.cmod.components.offline;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class MsiLayout {
    /** A package that, for this prefix, places nothing at all. */
    public static final class NothingToInstall extends IOException {
        NothingToInstall(String m) { super(m); }
    }

    private static final String SKIP = "\u0000skip";
    private static final int COMPONENT_64BIT = 0x100;
    private static final int SET_PROPERTY = 51, SET_DIRECTORY = 35, ERROR_ACTION = 19, EXE_IN_FOLDER = 34;
    private static final String SFP_CACHE_ACTION = "CDirSystemSFPCacheDir";
    private static final String[] HANDLED_ACTIONS = {"Wdsfpca_", "SxsInstallCA", "SxsUninstallCA"};
    private static final String[] UNSUPPORTED_TABLES = {"SelfReg", "Class", "ProgId", "TypeLib", "Extension", "Verb",
            "MIME", "AppId", "IniFile", "ServiceInstall", "MoveFile", "IsolatedComponent", "PublishComponent",
            "ODBCDriver", "ODBCDataSource"};
    private static final String[] SKIPPED_FOLDERS = {"TempFolder", "DesktopFolder", "ProgramMenuFolder", "StartMenuFolder",
            "StartupFolder", "SendToFolder", "FavoritesFolder", "NetHoodFolder", "PrintHoodFolder", "RecentFolder",
            "TemplateFolder", "MyPicturesFolder", "AdminToolsFolder"};

    private final MsiDatabase pkg;
    private final File out;
    private final OfflineTools tools;
    private final OfflineTools.Progress span;
    public final List<String> notes = new ArrayList<>();
    public final List<RegValue> registry = new ArrayList<>();
    /** Packages this one installs with its own msiexec run (wine-mono's support package), as laid out under [out]. */
    public final List<File> nestedPackages = new ArrayList<>();
    public int fileCount, assemblyCount, registryCount;
    private int missingFiles;
    /** The custom actions that run code: {name, condition, target}, kept until the features are chosen. */
    private final List<String[]> codeActions = new ArrayList<>();
    private final Map<String, String> files = new HashMap<>();
    private final Map<String, String> knownFolders = new LinkedHashMap<>();
    private final Map<String, String> props = new HashMap<>();
    private final Map<String, Map<String, String>> directories = new HashMap<>();
    private final Map<String, Map<String, String>> components = new LinkedHashMap<>();
    private final Set<String> includedFeatures = new HashSet<>();
    private final Set<String> includedComponents = new HashSet<>();
    private final Set<String> resolving = new HashSet<>();
    private final Set<String> setByAction = new HashSet<>();
    private final MsiConditions conditions;


    public MsiLayout(MsiDatabase pkg, File out, String user, OfflineTools tools, OfflineTools.Progress span) throws IOException {
        this.pkg = pkg; this.out = out; this.tools = tools; this.span = span;
        String u = "C:\\users\\" + user + "\\";
        String[][] known = {
                {"TARGETDIR", "C:\\"}, {"ROOTDRIVE", "C:\\"}, {"WindowsVolume", "C:\\"},
                {"WindowsFolder", "C:\\windows\\"},
                {"SystemFolder", "C:\\windows\\syswow64\\"},
                {"System64Folder", "C:\\windows\\system32\\"},
                {"System16Folder", "C:\\windows\\system\\"},
                {"FontsFolder", "C:\\windows\\Fonts\\"},
                {"ProgramFilesFolder", "C:\\Program Files (x86)\\"},
                {"ProgramFiles64Folder", "C:\\Program Files\\"},
                {"CommonFilesFolder", "C:\\Program Files (x86)\\Common Files\\"},
                {"CommonFiles64Folder", "C:\\Program Files\\Common Files\\"},
                {"CommonAppDataFolder", "C:\\ProgramData\\"},
                {"AppDataFolder", u + "AppData\\Roaming\\"},
                {"LocalAppDataFolder", u + "AppData\\Local\\"},
                {"PersonalFolder", u + "Documents\\"},
        };
        for (String[] k : known) knownFolders.put(k[0], k[1]);
        for (String k : SKIPPED_FOLDERS) knownFolders.put(k, SKIP);

        String[][] system = {{"VersionNT", "603"}, {"VersionNT64", "603"}, {"WindowsBuild", "9600"}, {"ServicePackLevel", "0"},
                {"Msix64", "6"}, {"ALLUSERS", "1"}, {"Privileged", "1"}, {"AdminUser", "1"}, {"MsiNTProductType", "1"},
                {"UILevel", "2"}, {"INSTALLLEVEL", "1"}, {"VersionMsi", "5.00"}};
        for (String[] s : system) props.put(s[0], s[1]);
        String platform = platformOf(pkg);
        if (platform.isEmpty() || platform.equals("intel")) props.put("Intel", "6");
        for (Map.Entry<String, String> e : knownFolders.entrySet()) props.put(e.getKey(), e.getValue());
        for (Map<String, String> row : pkg.table("Property")) {
            String p = row.get("Property");
            // The package's own defaults, but never over the folders and the system it runs on.
            if (!props.containsKey(p) || p.equals("INSTALLLEVEL") || p.equals("ALLUSERS") || p.equals("UILevel")) {
                props.put(p, row.get("Value"));
            }
        }
        props.put("ALLUSERS", "1");
        for (Map<String, String> row : pkg.table("Directory")) directories.put(row.get("Directory"), row);
        for (Map<String, String> row : pkg.table("Component")) components.put(row.get("Component"), row);
        conditions = new MsiConditions(props, n -> includedFeatures.contains(n) ? 3 : -1, n -> includedComponents.contains(n) ? 3 : -1);
    }

    /** The package's platform from its summary information ("intel", "x64", "arm64"...). */
    public static String platformOf(MsiDatabase pkg) {
        String template = pkg.summary("template");
        int semi = template.indexOf(';');
        return (semi >= 0 ? template.substring(0, semi) : template).trim().toLowerCase(Locale.ROOT);
    }

    public String product() {
        String v = props.get("ProductName");
        return v == null ? "" : v;
    }

    public String version() {
        String v = props.get("ProductVersion");
        return v == null ? "" : v;
    }

    private static int toInt(String v, int def) {
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String longName(String value) {
        if (value == null) return "";
        int bar = value.lastIndexOf('|');
        return bar >= 0 ? value.substring(bar + 1) : value;
    }

    /** C:\windows\x\ -> drive_c/windows/x; null for a path off drive C or a folder left out. */
    static String winToRel(String path) {
        if (path == null || path.isEmpty()) return null;
        Matcher m = Pattern.compile("^[Cc]:\\\\?(.*)$", Pattern.DOTALL).matcher(path);
        if (!m.matches()) return null;
        StringBuilder rel = new StringBuilder("drive_c");
        for (String part : m.group(1).replace('/', '\\').split("\\\\")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            rel.append('/').append(part);
        }
        return rel.toString();
    }

    static String relToWin(String rel, boolean folder) {
        String[] parts = rel.split("/");
        StringBuilder b = new StringBuilder("C:\\");
        for (int i = 1; i < parts.length; i++) {
            if (i > 1) b.append('\\');
            b.append(parts[i]);
        }
        if (folder && parts.length > 1) b.append('\\');
        return b.toString();
    }

    private static String cleanVersion(String version) {
        StringBuilder b = new StringBuilder();
        for (String p : version.split("\\.")) {
            if (p.isEmpty()) continue;
            if (b.length() > 0) b.append('.');
            b.append(toInt(p, 0));
        }
        return b.length() > 0 ? b.toString() : "0.0.0.0";
    }

    /** The .NET runtime an assembly was built for ("v4.0.30319"), from its metadata header. */
    static String clrVersion(File file) {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long size = f.length();
            byte[] buf = new byte[1 << 16];
            long pos = 0;
            while (pos < size) {
                f.seek(pos);
                int n = f.read(buf, 0, (int) Math.min(buf.length, size - pos));
                if (n <= 0) break;
                for (int i = 0; i + 3 < n; i++) {
                    if (buf[i] == 'B' && buf[i + 1] == 'S' && buf[i + 2] == 'J' && buf[i + 3] == 'B') {
                        byte[] head = new byte[16 + 32];
                        f.seek(pos + i);
                        int got = f.read(head);
                        if (got < 16) return "";
                        int length = CompoundFile.le32(head, 12);
                        int take = Math.max(0, Math.min(Math.min(length, 32), got - 16));
                        String s = new String(head, 16, take, StandardCharsets.US_ASCII);
                        int nul = s.indexOf('\0');
                        return nul >= 0 ? s.substring(0, nul) : s;
                    }
                }
                if (pos + n >= size) break;
                pos += n - 3;
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    // -- folders, formatted text

    /** The folder a Directory key resolves to, as drive_c/..., or null when it is left out. */
    String directory(String key) {
        if (key == null || resolving.contains(key)) return null;
        String value = props.get(key);
        Map<String, String> row = directories.get(key);
        // A folder Windows knows by name, one a custom action set, or a property naming a path.
        if (knownFolders.containsKey(key) || setByAction.contains(key) || row == null) {
            return value == null || value.isEmpty() || value.equals(SKIP) ? null : winToRel(value);
        }
        String parentKey = row.getOrDefault("Directory_Parent", "");
        String def = row.getOrDefault("DefaultDir", "");
        int colon = def.indexOf(':');
        def = longName(colon >= 0 ? def.substring(0, colon) : def);
        if (parentKey.isEmpty() || parentKey.equals(key)) return "drive_c";  // a root: TARGETDIR is drive C
        resolving.add(key);
        String parent;
        try {
            parent = directory(parentKey);
        } finally {
            resolving.remove(key);
        }
        if (parent == null) return null;
        if (def.equals(".") || def.isEmpty()) return parent;
        if (def.contains("\\") || def.contains("/") || def.equals("..")) return null;
        return parent + "/" + def;
    }

    private static final Pattern BRACKET = Pattern.compile("\\[([^\\[\\]]*)\\]");

    /** Formatted text: [Property], [Folder], [#file], [$component], [%env], [\x], [~]. */
    String format(String text) {
        if (text == null || text.isEmpty()) return text;
        String previous = null;
        for (int i = 0; i < 8 && !text.equals(previous); i++) {
            previous = text;
            Matcher m = BRACKET.matcher(text);
            StringBuffer sb = new StringBuffer();
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(one(m.group(1))));
            m.appendTail(sb);
            text = sb.toString();
        }
        return text;
    }

    private String one(String inner) {
        if (inner.startsWith("\\") && inner.length() == 2) return inner.substring(1);
        if (inner.equals("~")) return "\0";
        if (inner.startsWith("#") || inner.startsWith("!")) {
            String rel = files.get(inner.substring(1));
            return rel != null ? relToWin(rel, false) : "";
        }
        if (inner.startsWith("$")) {
            Map<String, String> comp = components.get(inner.substring(1));
            String rel = comp != null ? directory(comp.get("Directory_")) : null;
            return rel != null ? relToWin(rel, true) : "";
        }
        if (inner.startsWith("%")) return "";
        if (directories.containsKey(inner) || knownFolders.containsKey(inner)) {
            String rel = directory(inner);
            return rel != null ? relToWin(rel, true) : "";
        }
        String value = props.get(inner);
        return value == null || value.equals(SKIP) ? "" : value;
    }

    // -- what gets installed

    /** The property/folder-setting custom actions, in sequence order; the rest go to the notes. */
    private void runSetActions() throws IOException {
        Map<String, Map<String, String>> actions = new HashMap<>();
        for (Map<String, String> row : pkg.table("CustomAction")) actions.put(row.get("Action"), row);
        Map<String, Object[]> sequence = new LinkedHashMap<>();
        for (String table : new String[]{"InstallUISequence", "InstallExecuteSequence"}) {
            for (Map<String, String> row : pkg.table(table)) {
                String a = row.get("Action");
                if (actions.containsKey(a) && !sequence.containsKey(a)) {
                    sequence.put(a, new Object[]{toInt(row.get("Sequence"), 0), row.getOrDefault("Condition", "")});
                }
            }
        }
        List<Map.Entry<String, Object[]>> ordered = new ArrayList<>(sequence.entrySet());
        ordered.sort((x, y) -> Integer.compare((Integer) x.getValue()[0], (Integer) y.getValue()[0]));
        boolean sfp = pkg.hasTable("_SfpCaInf");
        for (Map.Entry<String, Object[]> e : ordered) {
            String name = e.getKey();
            String condition = (String) e.getValue()[1];
            Map<String, String> row = actions.get(name);
            int kind = toInt(row.get("Type"), 0) & 0x3F;
            boolean handled = false;
            for (String h : HANDLED_ACTIONS) if (name.startsWith(h)) handled = true;
            if (handled || (sfp && name.startsWith(SFP_CACHE_ACTION))) continue;
            if (kind == SET_PROPERTY || kind == SET_DIRECTORY) {
                if (!conditions.evaluate(condition, true)) continue;
                props.put(row.get("Source"), format(row.getOrDefault("Target", "")));
                setByAction.add(row.get("Source"));
            } else if (kind != ERROR_ACTION) {
                codeActions.add(new String[]{name, condition, kind == EXE_IN_FOLDER ? row.getOrDefault("Target", "") : ""});
            }
        }
    }

    private static final Pattern MSIEXEC_INSTALL = Pattern.compile("(?i)^\\s*\"?(?:[^\"]*\\\\)?msiexec(?:\\.exe)?\"?\\s+/i\\s+(?:\"([^\"]+)\"|(\\S+))");

    /**
     * The code-running custom actions whose conditions hold, now that the features are chosen (a
     * condition such as "&wine_mono=3" asks for one): one that installs a package this one placed
     * ("msiexec /i [$support]\winemono-support.msi") goes to [nestedPackages], the rest to the notes.
     */
    private void runCodeActions() {
        List<String> skipped = new ArrayList<>();
        for (String[] a : codeActions) {
            if (!conditions.evaluate(a[1], true)) continue;
            Matcher m = MSIEXEC_INSTALL.matcher(a[2].isEmpty() ? "" : format(a[2]));
            String rel = m.find() ? winToRel(m.group(1) != null ? m.group(1) : m.group(2)) : null;
            File nested = rel != null && rel.toLowerCase(Locale.ROOT).endsWith(".msi") ? new File(out, rel) : null;
            if (nested != null && nested.isFile()) {
                if (!nestedPackages.contains(nested)) nestedPackages.add(nested);
            } else {
                skipped.add(a[0]);
            }
        }
        if (!skipped.isEmpty()) {
            StringBuilder b = new StringBuilder("setup actions not run (they need the installer's own code): ");
            b.append(String.join(", ", skipped.subList(0, Math.min(8, skipped.size()))));
            if (skipped.size() > 8) b.append(" and ").append(skipped.size() - 8).append(" more");
            notes.add(b.toString());
        }
    }

    private void select() throws IOException {
        int installLevel = toInt(props.get("INSTALLLEVEL"), 1);
        if (installLevel == 0) installLevel = 1;
        Map<String, Map<String, String>> features = new LinkedHashMap<>();
        for (Map<String, String> row : pkg.table("Feature")) features.put(row.get("Feature"), new HashMap<>(row));
        for (Map<String, String> row : pkg.table("Condition")) {
            Map<String, String> f = features.get(row.get("Feature_"));
            if (f != null && conditions.evaluate(row.get("Condition"), false)) f.put("Level", row.get("Level"));
        }
        for (String name : features.keySet()) if (included(features, name, installLevel, 0)) includedFeatures.add(name);
        Map<String, Set<String>> mapped = new HashMap<>();
        for (Map<String, String> row : pkg.table("FeatureComponents")) {
            mapped.computeIfAbsent(row.get("Component_"), k -> new HashSet<>()).add(row.get("Feature_"));
        }
        for (Map.Entry<String, Map<String, String>> e : components.entrySet()) {
            Set<String> owners = mapped.get(e.getKey());
            if (owners != null) {
                boolean any = false;
                for (String o : owners) if (includedFeatures.contains(o)) any = true;
                if (!any) continue;
            }
            if (!conditions.evaluate(e.getValue().getOrDefault("Condition", ""), true)) continue;
            includedComponents.add(e.getKey());
        }
    }

    private static boolean included(Map<String, Map<String, String>> features, String name, int installLevel, int depth) {
        Map<String, String> f = features.get(name);
        if (f == null || depth > 32) return false;
        int level = toInt(f.get("Level"), 0);
        if (level <= 0 || level > installLevel) return false;
        String parent = f.getOrDefault("Feature_Parent", "");
        return parent.isEmpty() || parent.equals(name) || included(features, parent, installLevel, depth + 1);
    }

    private static final class Assembly {
        final int kind;
        final Map<String, String> ident;
        final String manifest;

        Assembly(int kind, Map<String, String> ident, String manifest) {
            this.kind = kind; this.ident = ident; this.manifest = manifest;
        }
    }

    /** Component -> where its files go when it is a global assembly (the GAC or winsxs). */
    private Map<String, Assembly> assemblies() throws IOException {
        Map<String, Map<String, String>> names = new HashMap<>();
        for (Map<String, String> row : pkg.table("MsiAssemblyName")) {
            names.computeIfAbsent(row.get("Component_"), k -> new HashMap<>()).put(row.get("Name").toLowerCase(Locale.ROOT), row.get("Value"));
        }
        Map<String, Assembly> placed = new HashMap<>();
        for (Map<String, String> row : pkg.table("MsiAssembly")) {
            String comp = row.get("Component_");
            if (!includedComponents.contains(comp) || !row.getOrDefault("File_Application", "").isEmpty()) continue;
            Map<String, String> ident = names.getOrDefault(comp, new HashMap<>());
            placed.put(comp, new Assembly(toInt(row.get("Attributes"), 0), ident, row.getOrDefault("File_Manifest", "")));
        }
        return placed;
    }

    private static String gacFolder(Map<String, String> ident, File assemblyFile) {
        String name = ident.getOrDefault("name", "");
        String token = ident.getOrDefault("publickeytoken", "").toLowerCase(Locale.ROOT);
        if (name.isEmpty() || token.isEmpty() || name.contains("/") || name.contains("\\")) return null;
        String version = cleanVersion(ident.getOrDefault("version", "0.0.0.0"));
        String culture = ident.getOrDefault("culture", "neutral");
        if (culture.equalsIgnoreCase("neutral")) culture = "";
        String arch = ident.getOrDefault("processorarchitecture", "").toLowerCase(Locale.ROOT);
        String gac;
        switch (arch) {
            case "x86": gac = "GAC_32"; break;
            case "amd64": case "x64": gac = "GAC_64"; break;
            case "msil": gac = "GAC_MSIL"; break;
            default: gac = arch.isEmpty() ? "GAC" : "GAC_MSIL"; break;
        }
        if (clrVersion(assemblyFile).startsWith("v4")) {
            return "drive_c/windows/Microsoft.NET/assembly/" + gac + "/" + name + "/v4.0_" + version + "_" + culture + "_" + token;
        }
        return "drive_c/windows/assembly/" + gac + "/" + name + "/" + version + "_" + culture + "_" + token;
    }

    /** Wine's winsxs names for a side-by-side assembly: {kind, folder, version}, or null. */
    private static String[] sxsNames(Map<String, String> ident) {
        String arch = ident.getOrDefault("processorarchitecture", "x86").toLowerCase(Locale.ROOT);
        if (arch.isEmpty()) arch = "x86";
        String name = ident.getOrDefault("name", "").toLowerCase(Locale.ROOT);
        String token = ident.getOrDefault("publickeytoken", "").toLowerCase(Locale.ROOT);
        String version = ident.getOrDefault("version", "");
        if (name.isEmpty() || token.isEmpty() || version.isEmpty() || name.contains("/") || name.contains("\\")) return null;
        if (ident.getOrDefault("type", "").equalsIgnoreCase("win32-policy")) {
            String target = name.replaceFirst("^policy\\.\\d+\\.\\d+\\.", "");
            return new String[]{"policy", "drive_c/windows/winsxs/policies/" + arch + "_" + target + "_" + token + "_none_deadbeef", version};
        }
        return new String[]{"assembly", arch + "_" + name + "_" + token + "_" + version + "_none_deadbeef", version};
    }

    /** Every cabinet in the package, unpacked into [work]; returns File key -> unpacked file. */
    private Map<String, File> unpack(File work) throws IOException {
        Map<String, File> found = new HashMap<>();
        List<Map<String, String>> media = new ArrayList<>();
        for (Map<String, String> row : pkg.table("Media")) if (!row.getOrDefault("Cabinet", "").isEmpty()) media.add(row);
        for (int index = 0; index < media.size(); index++) {
            String cabinet = media.get(index).get("Cabinet");
            span.report(0.05 + 0.3 * index / Math.max(1, media.size()), "unpacking " + cabinet.replaceFirst("^#", ""));
            File dest = new File(work, "media" + index);
            if (!dest.mkdirs()) throw new IOException("could not make " + dest);
            File cab;
            boolean own;
            if (cabinet.startsWith("#")) {
                cab = new File(work, "media" + index + ".cab");
                if (!pkg.extractStream(cabinet.substring(1), cab)) {
                    notes.add("cabinet " + cabinet + " is missing from the package");
                    continue;
                }
                own = true;
            } else {
                // An external cabinet: a file beside the package, as an installer wrapper lays it out.
                cab = OfflineTools.sibling(new File(pkg.path), cabinet);
                if (cab == null) {
                    notes.add("external cabinet " + cabinet + " is not part of the package; its files are missing");
                    continue;
                }
                own = false;
            }
            if (!tools.extract(cab, dest)) throw new IOException("could not unpack the cabinet " + cabinet);
            if (own) cab.delete();
            String base = dest.getPath() + File.separator;
            for (File f : OfflineTools.walkFiles(dest)) {
                String rel = f.getPath().substring(base.length()).replace(File.separatorChar, '/');
                found.putIfAbsent(rel, f);
                // A File key with backslashes in it (wine-mono's "bin\libmono-2.0-x86.dll") comes out of
                // the cabinet as folders, so it is found by that name too.
                if (rel.indexOf('/') >= 0) found.putIfAbsent(rel.replace('/', '\\'), f);
            }
        }
        return found;
    }

    private void put(File source, String rel) throws IOException {
        File target = new File(out, rel);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("could not make " + parent);
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private void installFiles(Map<String, File> unpacked) throws IOException {
        Map<String, Assembly> assemblies = assemblies();
        int missing = 0;
        Map<String, String> destinations = new LinkedHashMap<>();
        // Where each file goes: its component's folder, or the assembly store for a global assembly.
        for (Map<String, String> row : pkg.table("File")) {
            String comp = row.get("Component_");
            if (!includedComponents.contains(comp)) continue;
            String name = longName(row.get("FileName"));
            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) continue;
            String key = row.get("File");
            Assembly a = assemblies.get(comp);
            if (a != null) {
                if (a.kind == 0) {
                    File source = unpacked.get(components.get(comp).getOrDefault("KeyPath", ""));
                    String folder = source != null ? gacFolder(a.ident, source) : null;
                    destinations.put(key, folder != null ? folder + "/" + name : null);
                } else {
                    String[] names = sxsNames(a.ident);
                    if (names == null) destinations.put(key, null);
                    else if (names[0].equals("policy")) destinations.put(key, key.equals(a.manifest) ? names[1] + "/" + names[2] + ".policy" : null);
                    else if (key.equals(a.manifest)) destinations.put(key, "drive_c/windows/winsxs/manifests/" + names[1] + ".manifest");
                    else if (name.toLowerCase(Locale.ROOT).endsWith(".cat")) destinations.put(key, null);
                    else destinations.put(key, "drive_c/windows/winsxs/" + names[1] + "/" + name);
                }
                continue;
            }
            String folder = directory(components.get(comp).get("Directory_"));
            destinations.put(key, folder != null ? folder + "/" + name : null);
        }
        for (Map.Entry<String, String> e : destinations.entrySet()) if (e.getValue() != null) files.put(e.getKey(), e.getValue());
        Set<String> assemblyFolders = new HashSet<>();
        List<Map.Entry<String, String>> todo = new ArrayList<>();
        for (Map.Entry<String, String> e : destinations.entrySet()) if (e.getValue() != null) todo.add(e);
        int last = -1;
        for (int done = 0; done < todo.size(); done++) {
            int step = done * 20 / Math.max(1, todo.size());
            if (step != last) {
                last = step;
                span.report(0.35 + 0.55 * done / Math.max(1, todo.size()), "placing files (" + done + " of " + todo.size() + ")");
            }
            File source = unpacked.get(todo.get(done).getKey());
            String rel = todo.get(done).getValue();
            if (source == null) {
                missing++;
                continue;
            }
            put(source, rel);
            fileCount++;
            if (rel.contains("/assembly/") || rel.contains("/winsxs/")) assemblyFolders.add(rel.substring(0, rel.lastIndexOf('/')));
        }
        assemblyCount = assemblyFolders.size();
        for (Map<String, String> row : pkg.table("DuplicateFile")) {
            String fileKey = row.get("File_");
            if (!includedComponents.contains(row.get("Component_")) || !files.containsKey(fileKey)) continue;
            String src = files.get(fileKey);
            String folder = !row.getOrDefault("DestFolder", "").isEmpty() ? directory(row.get("DestFolder")) : src.substring(0, src.lastIndexOf('/'));
            String name = longName(row.getOrDefault("DestName", ""));
            if (name.isEmpty()) name = src.substring(src.lastIndexOf('/') + 1);
            File source = new File(out, src);
            if (folder != null && source.isFile() && !name.contains("/")) {
                put(source, folder + "/" + name);
                fileCount++;
            }
        }
        missingFiles = missing;
        if (missing > 0) notes.add(missing + " file(s) were not in the package's cabinets");
    }

    private void value(String hive, String key, String name, String kind, Object data, boolean wow64) {
        // A 32-bit component writes through the 32-bit view, as Wine's msi does: HKLM\Software is
        // Software\Wow6432Node, and the prefix's link keys there lead to the keys both views share.
        String lower = key.toLowerCase(Locale.ROOT);
        if (hive.equals("HKLM") && wow64 && lower.startsWith("software\\") && !lower.startsWith("software\\wow6432node\\")) {
            key = "Software\\Wow6432Node\\" + key.substring("software\\".length());
        }
        registry.add(new RegValue(hive, key, name, kind, data));
    }

    /** A Registry table Value: {type, data}. */
    private Object[] registryData(String raw) {
        if (raw == null || raw.isEmpty()) return new Object[]{"sz", ""};
        if (raw.startsWith("#x") || raw.startsWith("#X")) {
            String hex = format(raw.substring(2)).replaceAll("[^0-9A-Fa-f]", "");
            if (hex.length() % 2 == 1) hex = "0" + hex;
            return new Object[]{"binary", hex.toLowerCase(Locale.ROOT)};
        }
        if (raw.startsWith("#%")) return new Object[]{"expand_sz", format(raw.substring(2))};
        if (raw.startsWith("##")) return new Object[]{"sz", format(raw.substring(1))};
        if (raw.startsWith("#")) {
            String number = format(raw.substring(1));
            if (number.trim().matches("[+-]?\\d+")) {
                try {
                    return new Object[]{"dword", Long.parseLong(number.trim().replace("+", "")) & 0xFFFFFFFFL};
                } catch (NumberFormatException ignored) {
                }
            }
            return new Object[]{"sz", number};
        }
        String text = format(raw);
        if (text.indexOf('\0') >= 0) {
            List<String> parts = new ArrayList<>();
            for (String p : text.split("\u0000")) if (!p.isEmpty()) parts.add(p);
            return new Object[]{"multi_sz", parts};
        }
        return new Object[]{"sz", text};
    }

    private void installRegistry() throws IOException {
        for (Map<String, String> row : pkg.table("Registry")) {
            String comp = row.get("Component_");
            if (!includedComponents.contains(comp)) continue;
            int root = toInt(row.get("Root"), 2);
            String hive, prefix;
            switch (root) {
                case -1: case 2: hive = "HKLM"; prefix = ""; break;
                case 0: hive = "HKLM"; prefix = "Software\\Classes\\"; break;
                case 1: hive = "HKCU"; prefix = ""; break;
                default: continue;
            }
            String key = prefix + stripBackslashes(format(row.get("Key")));
            boolean wow64 = (toInt(components.get(comp).get("Attributes"), 0) & COMPONENT_64BIT) == 0;
            String name = row.getOrDefault("Name", "");
            String raw = row.getOrDefault("Value", "");
            if ((name.equals("+") || name.equals("-") || name.equals("*")) && raw.isEmpty() || name.isEmpty() && raw.isEmpty()) {
                value(hive, key, null, "key", null, wow64);
                continue;
            }
            Object[] td = registryData(raw);
            String formatted = format(name);
            value(hive, key, formatted == null ? "" : formatted, (String) td[0], td[1], wow64);
            registryCount++;
        }
        for (Map<String, String> row : pkg.table("Environment")) {
            if (!includedComponents.contains(row.get("Component_"))) continue;
            String full = row.get("Name");
            Matcher fm = Pattern.compile("^[=+\\-!*]*").matcher(full);
            String flags = fm.find() ? fm.group() : "";
            String name = full.substring(flags.length());
            if (flags.contains("!") || name.isEmpty()) continue;
            String raw = row.getOrDefault("Value", "");
            String hive = flags.contains("*") ? "HKLM" : "HKCU";
            String key = flags.contains("*") ? "System\\CurrentControlSet\\Control\\Session Manager\\Environment" : "Environment";
            String mode = "set";
            if (raw.startsWith("[~]")) {
                mode = "append";
                raw = raw.substring(3);
            } else if (raw.endsWith("[~]")) {
                mode = "prepend";
                raw = raw.substring(0, raw.length() - 3);
            }
            String separator = ";";
            if (mode.equals("append") && !raw.isEmpty() && ";:".indexOf(raw.charAt(0)) >= 0) {
                separator = raw.substring(0, 1);
                raw = raw.substring(1);
            } else if (mode.equals("prepend") && !raw.isEmpty() && ";:".indexOf(raw.charAt(raw.length() - 1)) >= 0) {
                separator = raw.substring(raw.length() - 1);
                raw = raw.substring(0, raw.length() - 1);
            }
            String v = format(raw);
            if (v != null && !v.isEmpty()) {
                // A plain "set" is written as a string value.
                registry.add(new RegValue(hive, key, name, mode.equals("set") ? "sz" : mode, v, separator));
                registryCount++;
            }
        }
        for (Map<String, String> row : pkg.table("Font")) {
            String rel = files.get(row.get("File_"));
            if (rel == null) continue;
            String fileName = rel.substring(rel.lastIndexOf('/') + 1);
            String title = row.getOrDefault("FontTitle", "");
            if (title.isEmpty()) {
                int dot = fileName.lastIndexOf('.');
                title = (dot > 0 ? fileName.substring(0, dot) : fileName) + " (TrueType)";
            }
            value("HKLM", "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", title, "sz", fileName, false);
            registryCount++;
        }
    }

    private static String stripBackslashes(String s) {
        int a = 0, b = s.length();
        while (a < b && s.charAt(a) == '\\') a++;
        while (b > a && s.charAt(b - 1) == '\\') b--;
        return s.substring(a, b);
    }

    private void check() throws IOException {
        for (Map<String, String> row : pkg.table("LaunchCondition")) {
            if (!conditions.evaluate(row.get("Condition"), true)) notes.add("the package says: " + format(row.getOrDefault("Description", "")).trim());
        }
        for (String name : UNSUPPORTED_TABLES) {
            int count = pkg.hasTable(name) ? pkg.table(name).size() : 0;
            if (count > 0) notes.add(count + (name.equals("SelfReg") ? " self-registering DLL(s) not registered" : " " + name + " entries not written"));
        }
    }

    /** Lays the package out under [out]; [work] is a scratch folder for its cabinets. */
    public void install(File work) throws IOException {
        String subject = pkg.summary("subject");
        span.report(0.0, "reading " + (subject.isEmpty() ? new File(pkg.path).getName() : subject));
        runSetActions();
        select();
        File scratch = new File(work, ".msi-" + System.nanoTime());
        if (!scratch.mkdirs()) throw new IOException("could not make " + scratch);
        try {
            Map<String, File> unpacked = unpack(scratch);
            span.report(0.35, "placing files");
            installFiles(unpacked);
        } finally {
            OfflineTools.deleteTree(scratch);
        }
        span.report(0.9, "writing the registry values");
        installRegistry();
        runCodeActions();
        check();
        if (fileCount == 0 && registry.isEmpty()) {
            if (missingFiles > 0) throw new NothingToInstall("none of the package's " + missingFiles + " files were found in its cabinets");
            throw new NothingToInstall("the package installs nothing here");
        }
        span.report(1.0, "done");
    }

    /** Groups the values by key for a quick look (tests). */
    public Map<String, Integer> valuesByHive() {
        Map<String, Integer> m = new TreeMap<>();
        for (RegValue v : registry) m.merge(v.hive, 1, Integer::sum);
        return m;
    }
}
