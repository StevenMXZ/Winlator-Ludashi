package com.winlator.cmod.contents;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.StreamUtils;
import com.winlator.cmod.core.StringUtils;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class WrapperManager {
    private static final String TAG = "WrapperManager";
    private static final String DIR_NAME = "wrappers";
    private static final String MARKER_ENTRY = "usr/lib/libvulkan_wrapper.so";

    public static final String DEFAULT_WRAPPER = "default";
    public static final String DEFAULT_LABEL = "Default";
    public static final String EXTRA_KEY = "vkWrapper";

    public static final class Imported {
        public final String identifier;
        public final String label;

        Imported(String identifier, String label) {
            this.identifier = identifier;
            this.label = label;
        }
    }

    private final Context context;
    private final File wrappersDir;

    public WrapperManager(Context context) {
        this.context = context;
        this.wrappersDir = new File(context.getFilesDir(), DIR_NAME);
    }

    public File fileFor(String identifier) {
        return new File(wrappersDir, identifier + ".tzst");
    }

    private File metaFor(String identifier) {
        return new File(wrappersDir, identifier + ".meta");
    }

    public boolean isDefault(String identifier) {
        return identifier == null || identifier.isEmpty() || DEFAULT_WRAPPER.equals(identifier);
    }

    public boolean isImported(String identifier) {
        if (isDefault(identifier)) return false;
        return fileFor(identifier).isFile() && metaFor(identifier).isFile();
    }

    public List<Imported> enumerateImported() {
        ArrayList<Imported> result = new ArrayList<>();
        File[] files = wrappersDir.listFiles();
        if (files == null) return result;
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".meta")) continue;
            String identifier = name.substring(0, name.length() - ".meta".length());
            if (isDefault(identifier) || !fileFor(identifier).isFile()) continue;
            result.add(new Imported(identifier, readLabel(file, identifier)));
        }
        Collections.sort(result, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return result;
    }

    public String labelFor(String identifier) {
        if (isDefault(identifier)) return DEFAULT_LABEL;
        File meta = metaFor(identifier);
        return meta.isFile() ? readLabel(meta, identifier) : identifier;
    }

    public String identifierForLabel(String label) {
        if (label == null || label.equals(DEFAULT_LABEL)) return DEFAULT_WRAPPER;
        for (Imported imported : enumerateImported()) {
            if (imported.label.equals(label)) return imported.identifier;
        }
        return DEFAULT_WRAPPER;
    }

    public List<String> labels() {
        ArrayList<String> labels = new ArrayList<>();
        labels.add(DEFAULT_LABEL);
        for (Imported imported : enumerateImported()) labels.add(imported.label);
        return labels;
    }

    public String resolve(String identifier) {
        return isImported(identifier) ? identifier : DEFAULT_WRAPPER;
    }

    private String readLabel(File meta, String fallback) {
        try (BufferedReader reader = new BufferedReader(new FileReader(meta))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("label=")) {
                    String label = line.substring("label=".length()).trim();
                    if (!label.isEmpty()) return label;
                }
            }
        }
        catch (IOException ignored) {}
        return fallback;
    }

    public static String cleanDisplayName(String name) {
        if (name == null) return "";
        String result = name.trim();
        String lower = result.toLowerCase(Locale.ENGLISH);
        String[] suffixes = {".tar.zst", ".tzst", ".zst", ".wcp"};
        for (String suffix : suffixes) {
            if (lower.endsWith(suffix)) {
                result = result.substring(0, result.length() - suffix.length());
                break;
            }
        }
        return result.trim();
    }

    public static boolean containsWrapperEntry(File archive) {
        if (archive == null || !archive.isFile()) return false;
        try (InputStream input = new BufferedInputStream(new FileInputStream(archive), StreamUtils.BUFFER_SIZE);
             ZstdCompressorInputStream zstd = new ZstdCompressorInputStream(input);
             TarArchiveInputStream tar = new TarArchiveInputStream(zstd)) {
            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry) tar.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("./")) name = name.substring(2);
                if (name.equals(MARKER_ENTRY)) return true;
            }
        }
        catch (IOException | RuntimeException e) {
            Log.d(TAG, "containsWrapperEntry: " + e.getMessage());
        }
        return false;
    }

    public String importWrapper(Uri source, String displayName) {
        if (source == null) return null;
        String baseLabel = cleanDisplayName(displayName);
        if (baseLabel.isEmpty()) return null;

        String label = baseLabel;
        String identifier = StringUtils.parseIdentifier(label);
        int suffix = 2;
        while (identifier.isEmpty() || isDefault(identifier) || fileFor(identifier).exists() || metaFor(identifier).exists()) {
            label = baseLabel + " " + suffix;
            identifier = StringUtils.parseIdentifier(label);
            suffix++;
            if (suffix > 999) return null;
        }

        if (!wrappersDir.isDirectory() && !wrappersDir.mkdirs()) return null;
        File temp = new File(wrappersDir, identifier + ".tzst.tmp");
        if (temp.exists()) temp.delete();

        try (InputStream input = context.getContentResolver().openInputStream(source);
             OutputStream output = new FileOutputStream(temp)) {
            if (input == null || !StreamUtils.copy(input, output)) {
                temp.delete();
                return null;
            }
        }
        catch (IOException | SecurityException e) {
            Log.d(TAG, "importWrapper copy failed: " + e.getMessage());
            temp.delete();
            return null;
        }

        if (!containsWrapperEntry(temp)) {
            temp.delete();
            return null;
        }

        File destination = fileFor(identifier);
        if (destination.exists()) destination.delete();
        if (!temp.renameTo(destination)) {
            temp.delete();
            return null;
        }

        try (OutputStream output = new FileOutputStream(metaFor(identifier))) {
            output.write(("label=" + label + "\n").getBytes());
        }
        catch (IOException e) {
            destination.delete();
            return null;
        }
        return identifier;
    }

    public boolean isInUse(String identifier) {
        if (isDefault(identifier)) return false;
        try {
            ContainerManager manager = new ContainerManager(context);
            for (Container container : manager.getContainers()) {
                if (identifier.equals(container.getVkWrapper())) return true;
            }
            for (Shortcut shortcut : manager.loadShortcuts()) {
                if (identifier.equals(shortcut.getExtra(EXTRA_KEY, ""))) return true;
            }
        }
        catch (Exception e) {
            Log.d(TAG, "isInUse: " + e.getMessage());
        }
        return false;
    }

    public void deleteImported(String identifier) {
        if (isDefault(identifier)) return;
        try {
            ContainerManager manager = new ContainerManager(context);
            for (Container container : manager.getContainers()) {
                if (identifier.equals(container.getVkWrapper())) {
                    container.setVkWrapper(DEFAULT_WRAPPER);
                    container.saveData();
                }
            }
            for (Shortcut shortcut : manager.loadShortcuts()) {
                if (identifier.equals(shortcut.getExtra(EXTRA_KEY, ""))) {
                    shortcut.putExtra(EXTRA_KEY, null);
                    shortcut.saveData();
                }
            }
        }
        catch (Exception e) {
            Log.d(TAG, "deleteImported cascade: " + e.getMessage());
        }
        FileUtils.delete(fileFor(identifier));
        FileUtils.delete(metaFor(identifier));
    }
}
