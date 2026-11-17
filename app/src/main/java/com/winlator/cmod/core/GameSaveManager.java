package com.winlator.cmod.core;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Per-game save discovery, backup and restore.
 *
 * Backups live beside the public artwork directories:
 *   /storage/emulated/0/Winlator/Saves/<Game Name>/
 *
 * Save locations are discovered under the Wine user's common save roots, then persisted in
 * save.json so discovery only has to succeed once. Automatic backups are intentionally scoped:
 * if no per-game root is known they do nothing instead of archiving the whole Wine profile.
 */
public final class GameSaveManager {
    private static final String TAG = "GameSaveManager";
    public static final String EXTRA_ENABLED = "gameSavesEnabled";
    public static final String EXTRA_AUTO_BACKUP = "autoSaveBackup";
    public static final String PREF_ALL_SHORTCUTS = "game_saves_all_shortcuts";
    private static final String AUTO_FILE = "auto-latest.zip";
    private static final String MAP_FILE = "save.json";
    private static final int KEEP_THRESHOLD = 50;

    private static final String[] SAVE_ROOTS = {
            "Documents/My Games",
            "Saved Games",
            "AppData/Roaming",
            "Documents",
            "AppData/Local",
            "AppData/LocalLow"
    };

    private static final String[] IDENTITY_ROOTS = {
            "AppData/Roaming/FLT",
            "AppData/Roaming/GSE Saves/settings"
    };

    private GameSaveManager() {}

    public static final class BackupResult {
        public final boolean ok;
        public final String path;
        public final int fileCount;
        public final boolean wholeProfile;
        public final String error;

        BackupResult(boolean ok, String path, int fileCount, boolean wholeProfile, String error) {
            this.ok = ok;
            this.path = path;
            this.fileCount = fileCount;
            this.wholeProfile = wholeProfile;
            this.error = error;
        }
    }

    public static final class RestoreResult {
        public final boolean ok;
        public final int fileCount;
        public final String error;

        RestoreResult(boolean ok, int fileCount, String error) {
            this.ok = ok;
            this.fileCount = fileCount;
            this.error = error;
        }
    }

    private static final class Candidate {
        final String relPath;
        final int score;

        Candidate(String relPath, int score) {
            this.relPath = relPath;
            this.score = score;
        }
    }

    public static File getGameDir(Shortcut shortcut) {
        File root = new File(Environment.getExternalStorageDirectory(), "Winlator/Saves");
        File named = new File(root, sanitize(shortcut.name));
        if (!named.isDirectory() && (!shortcut.getExtra("gogGameId").isEmpty() || !shortcut.getExtra("ludashiStore").isEmpty())) {
            File legacy = new File(root, sanitize(FileUtils.getBasename(shortcut.file.getPath())));
            if (legacy.isDirectory()) return legacy;
        }
        return named;
    }

    public static boolean isEnabled(Shortcut shortcut) {
        return "1".equals(shortcut.getExtra(EXTRA_ENABLED, "0"));
    }

    public static void setEnabled(Shortcut shortcut, boolean enabled) {
        shortcut.putExtra(EXTRA_ENABLED, enabled ? "1" : "0");
        if (!enabled) shortcut.putExtra(EXTRA_AUTO_BACKUP, "0");
        shortcut.saveData();
    }

    public static boolean isAutoBackupEnabled(Shortcut shortcut) {
        return "1".equals(shortcut.getExtra(EXTRA_AUTO_BACKUP, "0"));
    }

    public static void setAutoBackupEnabled(Shortcut shortcut, boolean enabled) {
        shortcut.putExtra(EXTRA_AUTO_BACKUP, enabled ? "1" : "0");
        if (enabled) shortcut.putExtra(EXTRA_ENABLED, "1");
        shortcut.saveData();
    }

    public static boolean isGlobalAutoBackupEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_ALL_SHORTCUTS, false);
    }

    public static boolean shouldAutoBackup(Context context, Shortcut shortcut) {
        return isGlobalAutoBackupEnabled(context)
                || (isEnabled(shortcut) && isAutoBackupEnabled(shortcut));
    }

    public static File getLatestBackup(Shortcut shortcut) {
        File dir = getGameDir(shortcut);
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".zip")
                && !name.endsWith(".tmp"));
        if (files == null || files.length == 0) return null;
        File latest = files[0];
        for (int i = 1; i < files.length; i++) {
            if (files[i].lastModified() > latest.lastModified()) latest = files[i];
        }
        return latest;
    }

    public static List<String> getSaveRoots(Shortcut shortcut) {
        List<String> remembered = loadMap(shortcut);
        if (!remembered.isEmpty()) return remembered;
        return rediscoverSaveRoots(shortcut);
    }

    public static List<String> rediscoverSaveRoots(Shortcut shortcut) {
        List<String> roots = discover(shortcut);
        if (!roots.isEmpty()) saveMap(shortcut, roots);
        return roots;
    }

    public static BackupResult backup(Shortcut shortcut, boolean automatic) {
        try {
            File profile = profileDir(shortcut);
            if (!profile.isDirectory()) {
                return new BackupResult(false, null, 0, false, "Wine profile not found");
            }

            List<String> roots = getSaveRoots(shortcut);
            boolean wholeProfile = roots.isEmpty();
            if (automatic && wholeProfile) {
                return new BackupResult(false, null, 0, false, "No per-game save location detected");
            }

            File gameDir = getGameDir(shortcut);
            if (!gameDir.exists() && !gameDir.mkdirs()) {
                return new BackupResult(false, null, 0, wholeProfile, "Could not create save folder");
            }

            File out = automatic
                    ? new File(gameDir, AUTO_FILE)
                    : new File(gameDir, sanitize(shortcut.name) + "_" + System.currentTimeMillis() + ".zip");
            File tmp = new File(gameDir, out.getName() + ".tmp");
            if (tmp.exists()) tmp.delete();

            List<String> effectiveRoots = wholeProfile ? null : new ArrayList<>(roots);
            if (effectiveRoots != null) {
                for (String identityRoot : IDENTITY_ROOTS) {
                    if (new File(profile, identityRoot).exists() && !effectiveRoots.contains(identityRoot)) {
                        effectiveRoots.add(identityRoot);
                    }
                }
            }

            int count = writeZip(profile, effectiveRoots, tmp);
            if (count == 0) {
                tmp.delete();
                return new BackupResult(false, null, 0, wholeProfile, "No save files to back up");
            }

            if (out.exists() && !out.delete()) {
                tmp.delete();
                return new BackupResult(false, null, count, wholeProfile, "Could not replace previous backup");
            }
            if (!tmp.renameTo(out)) {
                FileUtils.copy(tmp, out);
                tmp.delete();
            }
            if (!out.isFile()) {
                return new BackupResult(false, null, count, wholeProfile, "Could not finish backup");
            }

            return new BackupResult(true, out.getAbsolutePath(), count, wholeProfile, null);
        } catch (Exception e) {
            Log.e(TAG, "Backup failed", e);
            return new BackupResult(false, null, 0, false,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    public static RestoreResult restoreLatest(Shortcut shortcut) {
        File archive = getLatestBackup(shortcut);
        if (archive == null) return new RestoreResult(false, 0, "No backup found");

        try {
            File profile = profileDir(shortcut);
            if (!profile.exists() && !profile.mkdirs()) {
                return new RestoreResult(false, 0, "Could not create Wine profile");
            }
            File canonicalProfile = profile.getCanonicalFile();
            String base = canonicalProfile.getPath() + File.separator;
            int written = 0;

            try (ZipInputStream zis = new ZipInputStream(
                    new BufferedInputStream(new FileInputStream(archive)))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String rel = remapArchiveEntry(entry.getName());
                    if (rel == null || rel.isEmpty()) {
                        zis.closeEntry();
                        continue;
                    }

                    File out = new File(canonicalProfile, rel).getCanonicalFile();
                    if (!out.getPath().startsWith(base)) {
                        zis.closeEntry();
                        continue;
                    }

                    if (entry.isDirectory()) {
                        out.mkdirs();
                    } else {
                        if (isIdentityPath(rel) && out.isFile()) {
                            zis.closeEntry();
                            continue;
                        }
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out))) {
                            byte[] buffer = new byte[64 * 1024];
                            int read;
                            while ((read = zis.read(buffer)) != -1) bos.write(buffer, 0, read);
                        }
                        written++;
                    }
                    zis.closeEntry();
                }
            }
            return new RestoreResult(true, written, null);
        } catch (Exception e) {
            Log.e(TAG, "Restore failed", e);
            return new RestoreResult(false, 0,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private static File profileDir(Shortcut shortcut) {
        return new File(shortcut.container.getRootDir(), ".wine/drive_c/users/" + ImageFs.USER);
    }

    private static int writeZip(File profile, List<String> roots, File out) throws IOException {
        List<File> starts = new ArrayList<>();
        if (roots == null) {
            starts.add(profile);
        } else {
            File profileCanon = profile.getCanonicalFile();
            String base = profileCanon.getPath() + File.separator;
            for (String rel : roots) {
                File f = new File(profileCanon, rel).getCanonicalFile();
                if ((f.getPath().equals(profileCanon.getPath()) || f.getPath().startsWith(base))
                        && f.exists()) starts.add(f);
            }
        }

        int count = 0;
        Set<String> seen = new HashSet<>();
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(out)))) {
            ArrayDeque<File> stack = new ArrayDeque<>(starts);
            while (!stack.isEmpty()) {
                File f = stack.removeLast();
                if (!f.exists() || Files.isSymbolicLink(f.toPath())) continue;
                String rel = relative(profile, f);
                if (f.isDirectory()) {
                    if (isNoiseDir(rel)) continue;
                    File[] children = f.listFiles();
                    if (children != null) {
                        for (File child : children) stack.addLast(child);
                    }
                    continue;
                }
                if (isFrontendShortcut(rel)) continue;
                if (!seen.add(rel)) continue;

                zos.putNextEntry(new ZipEntry(rel));
                try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f))) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) zos.write(buffer, 0, read);
                }
                zos.closeEntry();
                count++;
            }
        }
        return count;
    }

    private static List<String> loadMap(Shortcut shortcut) {
        File mapFile = new File(getGameDir(shortcut), MAP_FILE);
        if (!mapFile.isFile()) return Collections.emptyList();
        try {
            JSONObject json = new JSONObject(FileUtils.readString(mapFile));
            JSONArray arr = json.optJSONArray("roots");
            if (arr == null) return Collections.emptyList();
            File profile = profileDir(shortcut);
            List<String> valid = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String rel = arr.optString(i, "");
                if (!rel.isEmpty() && new File(profile, rel).exists()) valid.add(rel);
            }
            return valid;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static void saveMap(Shortcut shortcut, List<String> roots) {
        try {
            File dir = getGameDir(shortcut);
            dir.mkdirs();
            JSONObject json = new JSONObject();
            json.put("game", shortcut.name);
            json.put("roots", new JSONArray(roots));
            json.put("updatedAt", System.currentTimeMillis());
            FileUtils.writeString(new File(dir, MAP_FILE), json.toString(2));
        } catch (Exception e) {
            Log.w(TAG, "Could not persist save map", e);
        }
    }

    private static List<String> discover(Shortcut shortcut) {
        File profile = profileDir(shortcut);
        if (!profile.isDirectory()) return Collections.emptyList();

        List<String> identifiers = new ArrayList<>();
        addIdentifier(identifiers, shortcut.name);
        String exe = shortcut.path != null ? shortcut.path.replace('\\', '/') : "";
        int slash = exe.lastIndexOf('/');
        if (slash >= 0) exe = exe.substring(slash + 1);
        int dot = exe.lastIndexOf('.');
        if (dot > 0) exe = exe.substring(0, dot);
        addIdentifier(identifiers, exe);
        addIdentifier(identifiers, shortcut.wmClass);
        if (identifiers.isEmpty()) return Collections.emptyList();

        Map<String, Candidate> hits = new LinkedHashMap<>();
        for (String root : SAVE_ROOTS) {
            File rootDir = new File(profile, root);
            File[] first = rootDir.listFiles();
            if (first == null) continue;
            for (File d1 : first) {
                if (!d1.isDirectory()) continue;
                scoreCandidate(profile, d1, identifiers, hits);
                File[] second = d1.listFiles();
                if (second != null) {
                    for (File d2 : second) {
                        if (d2.isDirectory()) scoreCandidate(profile, d2, identifiers, hits);
                    }
                }
            }
        }

        List<Candidate> ranked = new ArrayList<>(hits.values());
        ranked.sort((a, b) -> Integer.compare(b.score, a.score));
        List<String> kept = new ArrayList<>();
        for (Candidate candidate : ranked) {
            boolean nested = false;
            for (String parent : kept) {
                if (candidate.relPath.equals(parent) || candidate.relPath.startsWith(parent + "/")) {
                    nested = true;
                    break;
                }
            }
            if (!nested) kept.add(candidate.relPath);
        }
        return kept;
    }

    private static void scoreCandidate(File profile, File dir, List<String> ids,
                                       Map<String, Candidate> out) {
        String candidateName = normalize(dir.getName());
        if (candidateName.isEmpty()) return;
        int best = 0;
        for (String id : ids) best = Math.max(best, score(id, candidateName));
        if (best < KEEP_THRESHOLD) return;

        String rel = relative(profile, dir);
        Candidate old = out.get(rel);
        if (old == null || best > old.score) out.put(rel, new Candidate(rel, best));
    }

    private static void addIdentifier(List<String> out, String raw) {
        String n = normalize(raw);
        if (!n.isEmpty() && !out.contains(n)) out.add(n);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT)
                .replace("®", "").replace("™", "").replace("©", "");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }

    private static int score(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 100;
        if (a.contains(b) || b.contains(a)) return 70;

        int max = Math.max(a.length(), b.length());
        if (max > 0) {
            double ratio = 1.0 - (double) levenshtein(a, b) / max;
            if (ratio >= 0.85) return 50;
        }
        return 0;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    private static String relative(File profile, File f) {
        String base = profile.getAbsolutePath();
        String path = f.getAbsolutePath();
        if (path.equals(base)) return "";
        String rel = path.substring(Math.min(path.length(), base.length()));
        while (rel.startsWith(File.separator)) rel = rel.substring(1);
        return rel.replace(File.separatorChar, '/');
    }

    private static boolean isNoiseDir(String rel) {
        String p = rel.replace('\\', '/').toLowerCase(Locale.ROOT);
        return p.equals("appdata/local/temp")
                || p.startsWith("appdata/local/temp/")
                || p.equals("appdata/local/crashdumps")
                || p.startsWith("appdata/local/crashdumps/");
    }

    private static boolean isFrontendShortcut(String rel) {
        String p = rel.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!p.startsWith("desktop/")) return false;
        return p.endsWith(".lnk") || p.endsWith(".desktop") || p.endsWith(".url");
    }

    private static boolean isIdentityPath(String rel) {
        String p = rel.replace('\\', '/');
        for (String root : IDENTITY_ROOTS) {
            if (p.equalsIgnoreCase(root) || p.toLowerCase(Locale.ROOT)
                    .startsWith(root.toLowerCase(Locale.ROOT) + "/")) return true;
        }
        return false;
    }

    private static String remapArchiveEntry(String raw) {
        if (raw == null) return null;
        String p = raw.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) return null;

        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.startsWith("drive_c/users/")) {
            String[] parts = p.split("/", 4);
            if (parts.length < 4) return null;
            p = parts[3];
        }
        if (p.equals("..") || p.startsWith("../") || p.contains("/../")) return null;
        return p;
    }

    private static String sanitize(String name) {
        if (name == null) return "Game";
        String safe = name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "Game" : safe;
    }
}
