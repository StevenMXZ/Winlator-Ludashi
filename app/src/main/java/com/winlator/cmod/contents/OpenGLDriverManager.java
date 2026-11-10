package com.winlator.cmod.contents;

import android.content.Context;
import android.net.Uri;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

/** Versioned OpenGL packages. Selecting a driver never overwrites imagefs/usr/lib. */
public final class OpenGLDriverManager {
    public static final String BUNDLED = "Bundled";
    private final Context context;
    public OpenGLDriverManager(Context context) { this.context = context.getApplicationContext(); }
    private File root() { return ContentsManager.getContentTypeDir(context, ContentProfile.ContentType.CONTENT_TYPE_FREEDRENO); }

    public static boolean isFreedreno(Context context, Uri uri) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(context.getContentResolver().openInputStream(uri))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null && ++count <= 4096) {
                if (entry.getName().equals("meta.json")) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096]; int n;
                    while ((n = zip.read(buf)) != -1) {
                        if (out.size() + n > 65536) throw new IOException("Driver metadata too large");
                        out.write(buf, 0, n);
                    }
                    return "freedreno".equalsIgnoreCase(new JSONObject(out.toString("UTF-8")).optString("type"));
                }
            }
        }
        return false;
    }

    public List<String> versions() {
        List<String> result = new ArrayList<>(); result.add(BUNDLED);
        File[] dirs = root().listFiles();
        if (dirs != null) for (File dir : dirs)
            if (!dir.getName().startsWith(".") && new File(dir, "meta.json").isFile()) result.add(dir.getName());
        Collections.sort(result.subList(1, result.size()));
        return result;
    }

    /** Stable IDs remain stored in settings; only presentation uses the Mesa version. */
    public String versionLabel(String id) {
        if (id == null || id.isEmpty() || BUNDLED.equals(id)) return "26.3.0";
        if (!id.equals(new File(id).getName()) || id.equals(".") || id.equals("..")) return id;
        try {
            JSONObject meta = new JSONObject(FileUtils.readString(new File(new File(root(), id), "meta.json")));
            String version = shortMesaVersion(meta.optString("driverVersion"));
            if (!version.isEmpty()) return version;
        } catch (Exception ignored) {}
        return id;
    }

    public static String shortMesaVersion(String raw) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("\\d+\\.\\d+\\.\\d+").matcher(raw == null ? "" : raw);
        return match.find() ? match.group() : "";
    }

    public Map<String, String> versionLabels(String selected) {
        List<String> ids = versions();
        if (selected != null && !selected.isEmpty() && !ids.contains(selected)) ids.add(selected);
        Map<String, String> labels = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (String id : ids) {
            String label = versionLabel(id);
            // Two builds may share the same Mesa version; keep each selectable.
            if (!used.add(label)) { label += " (" + id + ")"; used.add(label); }
            labels.put(id, label);
        }
        return labels;
    }

    public String install(Uri uri) throws Exception {
        root().mkdirs();
        File stage = Files.createTempDirectory(root().toPath(), ".import-").toFile();
        try {
            long total = 0; int count = 0;
            try (ZipInputStream zip = new ZipInputStream(context.getContentResolver().openInputStream(uri))) {
                ZipEntry entry; byte[] buf = new byte[16384];
                Set<String> names = new HashSet<>();
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (++count > 8 || entry.isDirectory() || !names.add(name)
                            || !(name.equals("meta.json") || name.equals("libGL.so.1") || name.matches("libgallium[^/\\\\]*\\.so")))
                        throw new IOException("Unexpected Freedreno package entry: " + name);
                    File dst = LocalDriverArchive.resolveEntry(stage, name);
                    try (OutputStream out = new FileOutputStream(dst)) {
                        int n; while ((n = zip.read(buf)) != -1) {
                            if ((total += n) > 256L * 1024 * 1024) throw new IOException("Driver too large");
                            out.write(buf, 0, n);
                        }
                    }
                }
            }
            if (new File(stage, "meta.json").length() > 65536) throw new IOException("Metadata too large");
            JSONObject meta = new JSONObject(FileUtils.readString(new File(stage, "meta.json")));
            if (!"freedreno".equalsIgnoreCase(meta.optString("type")) || meta.optInt("schemaVersion") != 1
                    || !"libGL.so.1".equals(meta.optString("libraryName"))) throw new IOException("Invalid Freedreno metadata");
            String name = meta.optString("name").trim(), version = meta.optString("packageVersion").trim();
            if (name.isEmpty() || version.isEmpty()) throw new IOException("Missing driver name/version");
            String id = (name + "-" + version).replaceAll("[^A-Za-z0-9._-]", "_") + "-1";
            File gl = new File(stage, "libGL.so.1");
            List<String> needs = ElfLibrary.dependencies(gl);
            String gallium = null;
            for (String lib : needs) if (lib.startsWith("libgallium") && lib.endsWith(".so")) gallium = lib;
            if (gallium == null || gallium.contains("/") || count != 3) throw new IOException("Missing paired Gallium dependency");
            ElfLibrary.dependencies(new File(stage, gallium));
            aliases(stage, gl);
            JSONObject profile = new JSONObject().put("type", "Freedreno").put("versionName", id.substring(0, id.length()-2))
                    .put("versionCode", 1).put("description", meta.optString("description", name)).put("files", new JSONArray());
            FileUtils.writeString(new File(stage, "profile.json"), profile.toString());
            File destination = new File(root(), id);
            if (destination.exists() || !stage.renameTo(destination)) throw new IOException("Driver version already installed");
            return id;
        } finally { FileUtils.delete(stage); }
    }

    private static void aliases(File dir, File gl) throws IOException {
        for (String alias : new String[]{"libGL.so", "libGL.so.1", "libGL.so.1.5.0"}) {
            File file = new File(dir, alias);
            if (!file.equals(gl) && !file.exists()) Files.copy(gl.toPath(), file.toPath());
        }
        File glapi = new File(dir, "libglapi.so.0.0.0");
        if (glapi.isFile()) for (String alias : new String[]{"libglapi.so", "libglapi.so.0"}) {
            File file = new File(dir, alias);
            if (!file.exists()) Files.copy(glapi.toPath(), file.toPath());
        }
    }

    public synchronized File resolve(String driver, String selected) throws Exception {
        if ("freedreno".equals(driver) && selected != null && !selected.isEmpty() && !BUNDLED.equals(selected)) {
            if (!versions().contains(selected)) throw new IOException("Selected Freedreno version is not installed: " + selected);
            File dir = new File(root(), selected);
            File[] gallium = dir.listFiles((parent, name) -> name.startsWith("libgallium") && name.endsWith(".so"));
            if (!new File(dir, "libGL.so.1").isFile() || !new File(dir, "libGL.so").isFile()
                    || gallium == null || gallium.length != 1 || !gallium[0].isFile())
                throw new IOException("Incomplete Freedreno installation");
            return dir;
        }
        File builtins = new File(context.getFilesDir(), "opengl-bundled-v1"); builtins.mkdirs();
        File destination = new File(builtins, driver);
        if (new File(destination, "libGL.so.1").isFile()) return destination;
        File stage = Files.createTempDirectory(builtins.toPath(), ".install-").toFile();
        try {
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context,
                    "graphics_driver/opengl_" + driver + ".tzst", stage)) throw new IOException("Cannot install bundled OpenGL driver");
            File libs = new File(stage, "usr/lib");
            File gl = new File(libs, "libGL.so.1");
            if (!gl.isFile()) gl = new File(libs, "libGL.so.1.5.0");
            if (!gl.isFile()) throw new IOException("Bundled libGL missing");
            aliases(libs, gl);
            if (destination.exists()) throw new IOException("Incomplete bundled driver installation");
            if (!libs.renameTo(destination)) throw new IOException("Cannot finalize bundled OpenGL driver");
            return destination;
        } finally { FileUtils.delete(stage); }
    }
}
