package com.winlator.cmod.core;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;

/* loaded from: classes10.dex */
public abstract class TarCompressorUtils {

    public interface ExclusionFilter {
        boolean shouldInclude(File file);
    }

    public enum Type {
        XZ,
        ZSTD
    }

    private static void addFile(ArchiveOutputStream tar, File file, String entryName) {
        try {
            tar.putArchiveEntry(tar.createArchiveEntry(file, entryName));
            BufferedInputStream inStream = new BufferedInputStream(new FileInputStream(file), 65536);
            try {
                StreamUtils.copy(inStream, tar);
                inStream.close();
                tar.closeArchiveEntry();
            } finally {
            }
        } catch (Exception e) {
        }
    }

    private static void addLinkFile(ArchiveOutputStream tar, File file, String entryName) {
        try {
            TarArchiveEntry entry = new TarArchiveEntry(entryName, TarConstants.LF_SYMLINK);
            entry.setLinkName(FileUtils.readSymlink(file));
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
        } catch (Exception e) {
        }
    }

    private static void addDirectory(ArchiveOutputStream tar, File folder, String basePath, ExclusionFilter filter) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (filter == null || filter.shouldInclude(file)) {
                if (FileUtils.isSymlink(file)) {
                    addLinkFile(tar, file, basePath + file.getName());
                } else if (file.isDirectory()) {
                    String entryName = basePath + file.getName() + "/";
                    tar.putArchiveEntry(tar.createArchiveEntry(folder, entryName));
                    tar.closeArchiveEntry();
                    addDirectory(tar, file, entryName, filter);
                } else {
                    addFile(tar, file, basePath + file.getName());
                }
            }
        }
    }

    public static void compress(Type type, File file, File destination, int level) {
        compress(type, new File[]{file}, destination, level, (ExclusionFilter) null);
    }

    public static void compress(Type type, File file, File destination, int level, ExclusionFilter filter) {
        compress(type, new File[]{file}, destination, level, filter);
    }

    public static void compress(Type type, File[] files, File destination, int level, ExclusionFilter filter) {
        try {
            OutputStream outStream = getCompressorOutputStream(type, destination, level);
            try {
                TarArchiveOutputStream tar = new TarArchiveOutputStream(outStream);
                try {
                    tar.setLongFileMode(2);
                    for (File file : files) {
                        if (filter == null || filter.shouldInclude(file)) {
                            if (FileUtils.isSymlink(file)) {
                                addLinkFile(tar, file, file.getName());
                            } else if (file.isDirectory()) {
                                String basePath = file.getName() + "/";
                                tar.putArchiveEntry(tar.createArchiveEntry(file, basePath));
                                tar.closeArchiveEntry();
                                addDirectory(tar, file, basePath, filter);
                            } else {
                                addFile(tar, file, file.getName());
                            }
                        }
                    }
                    tar.finish();
                    tar.close();
                    if (outStream != null) {
                        outStream.close();
                    }
                } finally {
                }
            } finally {
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static boolean extract(Type type, Context context, String assetFile, File destination) {
        return extract(type, context, assetFile, destination, (OnExtractFileListener) null);
    }

    public static boolean extract(Type type, Context context, String assetFile, File destination, OnExtractFileListener onExtractFileListener) {
        try {
            return extract(type, context.getAssets().open(assetFile), destination, onExtractFileListener);
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean extract(Type type, Context context, Uri source, File destination) {
        return extract(type, context, source, destination, (OnExtractFileListener) null);
    }

    public static boolean extract(Type type, Context context, Uri source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null) {
            return false;
        }
        try {
            if (source.toString().startsWith("/")) {
                return extract(type, new FileInputStream(source.toString()), destination, onExtractFileListener);
            }
            return extract(type, context.getContentResolver().openInputStream(source), destination, onExtractFileListener);
        } catch (FileNotFoundException e) {
            return false;
        }
    }

    public static boolean extract(Type type, File source, File destination) {
        return extract(type, source, destination, (OnExtractFileListener) null);
    }

    public static boolean extract(Type type, File source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null || !source.isFile()) {
            return false;
        }
        try {
            return extract(type, new BufferedInputStream(new FileInputStream(source), 65536), destination, onExtractFileListener);
        } catch (FileNotFoundException e) {
            return false;
        }
    }

    private static boolean extract(Type type, InputStream source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null) {
            return false;
        }
        try {
            InputStream inStream = getCompressorInputStream(type, source);
            try {
                ArchiveInputStream tar = new TarArchiveInputStream(inStream);
                while (true) {
                    try {
                        TarArchiveEntry entry = (TarArchiveEntry) tar.getNextEntry();
                        if (entry != null) {
                            if (tar.canReadEntryData(entry)) {
                                File file = new File(destination, entry.getName());
                                if (onExtractFileListener == null || (file = onExtractFileListener.onExtractFile(file, entry.getSize())) != null) {
                                    if (entry.isDirectory()) {
                                        if (!file.isDirectory()) {
                                            file.mkdirs();
                                        }
                                    } else if (entry.isSymbolicLink()) {
                                        FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                                    } else {
                                        BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(file), 65536);
                                        try {
                                            if (!StreamUtils.copy(tar, outStream)) {
                                                outStream.close();
                                                tar.close();
                                                if (inStream != null) {
                                                    inStream.close();
                                                }
                                                return false;
                                            }
                                            outStream.close();
                                        } finally {
                                        }
                                    }
                                    FileUtils.chmod(file, 505);
                                }
                            }
                        } else {
                            tar.close();
                            if (inStream != null) {
                                inStream.close();
                                return true;
                            }
                            return true;
                        }
                    } finally {
                    }
                }
            } finally {
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static InputStream getCompressorInputStream(Type type, InputStream source) throws IOException {
        if (type == Type.XZ) {
            return new XZCompressorInputStream(source);
        }
        if (type == Type.ZSTD) {
            return new ZstdCompressorInputStream(source);
        }
        return null;
    }

    private static OutputStream getCompressorOutputStream(Type type, File destination, int level) throws IOException {
        if (type == Type.XZ) {
            return new XZCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(destination), 65536), level);
        }
        if (type == Type.ZSTD) {
            return new ZstdCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(destination), 65536), level);
        }
        return null;
    }

    public static void archive(File[] files, File destination, ExclusionFilter filter) {
        try {
            OutputStream outStream = new BufferedOutputStream(new FileOutputStream(destination), 65536);
            try {
                TarArchiveOutputStream tar = new TarArchiveOutputStream(outStream);
                try {
                    tar.setLongFileMode(2);
                    for (File file : files) {
                        if (filter == null || filter.shouldInclude(file)) {
                            if (FileUtils.isSymlink(file)) {
                                addLinkFile(tar, file, file.getName());
                            } else if (file.isDirectory()) {
                                String basePath = file.getName() + "/";
                                tar.putArchiveEntry(tar.createArchiveEntry(file, basePath));
                                tar.closeArchiveEntry();
                                addDirectory(tar, file, basePath, filter);
                            } else {
                                addFile(tar, file, file.getName());
                            }
                        }
                    }
                    tar.finish();
                    tar.close();
                    outStream.close();
                } finally {
                }
            } finally {
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static boolean extractTar(File source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null || !source.isFile()) {
            return false;
        }
        try {
            InputStream inStream = new BufferedInputStream(new FileInputStream(source), 65536);
            try {
                TarArchiveInputStream tar = new TarArchiveInputStream(inStream);
                String topLevelDirectory = null;
                while (true) {
                    try {
                        TarArchiveEntry entry = (TarArchiveEntry) tar.getNextEntry();
                        if (entry != null) {
                            if (tar.canReadEntryData(entry)) {
                                String entryName = entry.getName();
                                if (topLevelDirectory == null && entry.isDirectory()) {
                                    topLevelDirectory = entryName;
                                } else if (entryName.contains("/tmp/")) {
                                    Log.d("RestoreOp", "Skipping tmp directory: " + entryName);
                                } else {
                                    String adjustedName = entryName.replaceFirst("^" + topLevelDirectory, "");
                                    File file = new File(destination, adjustedName);
                                    if (onExtractFileListener == null || (file = onExtractFileListener.onExtractFile(file, entry.getSize())) != null) {
                                        if (entry.isDirectory()) {
                                            if (!file.isDirectory()) {
                                                file.mkdirs();
                                            }
                                        } else if (entry.isSymbolicLink()) {
                                            FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                                        } else {
                                            BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(file), 65536);
                                            try {
                                                if (!StreamUtils.copy(tar, outStream)) {
                                                    outStream.close();
                                                    tar.close();
                                                    inStream.close();
                                                    return false;
                                                }
                                                outStream.close();
                                            } finally {
                                            }
                                        }
                                        FileUtils.chmod(file, 505);
                                    }
                                }
                            }
                        } else {
                            tar.close();
                            inStream.close();
                            return true;
                        }
                    } finally {
                    }
                }
            } finally {
            }
        } catch (IOException e) {
            Log.e("RestoreOp", "Failed to extract tar file", e);
            return false;
        }
    }
}
