package com.winlator.cmod.lsfg;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class LosslessScaling {
    public static final int STATUS_OK = 0;
    public static final int STATUS_NOT_INSTALLED = 1;
    public static final int STATUS_UNREADABLE_FILE = 2;
    public static final int STATUS_NOT_PORTABLE_EXECUTABLE = 3;
    public static final int STATUS_MISSING_SHADERS = 4;
    public static final int STATUS_INVALID_SHADERS = 5;
    public static final int STATUS_CACHE_UNUSABLE = 6;
    public static final int STATUS_TRANSLATION_FAILED = 7;

    public static final int VARIANT_NONE = 0;
    public static final int VARIANT_FP16 = 1;
    public static final int VARIANT_FP32 = 2;
    public static final int VARIANT_DXBC = 3;

    private static final String TAG = "LosslessScaling";
    private static final String STORE_DIR = "lsfg";
    private static final String CACHE_FP32 = "shaders-fp32.cache";
    private static final String CACHE_FP16 = "shaders-fp16.cache";
    private static final String STAGED_DLL = "Lossless.staged";

    static {
        System.loadLibrary("vulkan_renderer");
    }

    private LosslessScaling() {}

    public static File getStoreDir(Context context) {
        return new File(context.getFilesDir(), STORE_DIR);
    }

    public static File getCacheFile(Context context, boolean fp16) {
        return new File(getStoreDir(context), fp16 ? CACHE_FP16 : CACHE_FP32);
    }

    public static File resolveCacheFile(Context context, boolean preferFp16) {
        File preferred = getCacheFile(context, preferFp16);
        if (preferred.isFile()) return preferred;
        File fallback = getCacheFile(context, !preferFp16);
        return fallback.isFile() ? fallback : null;
    }

    public static int getStatus(Context context, boolean preferFp16) {
        File cache = resolveCacheFile(context, preferFp16);
        if (cache == null) return STATUS_NOT_INSTALLED;
        return nativeInspectCache(cache.getAbsolutePath());
    }

    public static int getVariant(Context context, boolean preferFp16) {
        File cache = resolveCacheFile(context, preferFp16);
        if (cache == null) return VARIANT_NONE;
        return nativeCacheVariant(cache.getAbsolutePath());
    }

    public static String variantName(int variant) {
        switch (variant) {
            case VARIANT_FP16: return "spirv-fp16";
            case VARIANT_FP32: return "spirv-fp32";
            case VARIANT_DXBC: return "dxbc-translated";
            default: return "none";
        }
    }

    public static synchronized int installFrom(Context context, File dll) {
        if (dll == null || !dll.isFile()) return STATUS_NOT_INSTALLED;
        File store = getStoreDir(context);
        if (!store.isDirectory() && !store.mkdirs()) return STATUS_CACHE_UNUSABLE;

        File pending32 = new File(store, CACHE_FP32 + ".pending");
        File pending16 = new File(store, CACHE_FP16 + ".pending");
        deleteQuietly(pending32);
        deleteQuietly(pending16);
        try {
            int status = nativeBuildCache(dll.getAbsolutePath(), pending32.getAbsolutePath(), false);
            if (status != STATUS_OK) return status;
            int variant = nativeCacheVariant(pending32.getAbsolutePath());
            if (variant == VARIANT_DXBC) {
                if (!pending32.renameTo(getCacheFile(context, false))) return STATUS_CACHE_UNUSABLE;
                deleteQuietly(getCacheFile(context, true));
                return STATUS_OK;
            }
            boolean onlyFp16 = variant == VARIANT_FP16;
            if (onlyFp16) {
                if (!pending32.renameTo(getCacheFile(context, true))) return STATUS_CACHE_UNUSABLE;
                deleteQuietly(getCacheFile(context, false));
                return STATUS_OK;
            }
            boolean hasFp16 = nativeBuildCache(dll.getAbsolutePath(), pending16.getAbsolutePath(), true) == STATUS_OK
                    && nativeCacheVariant(pending16.getAbsolutePath()) == VARIANT_FP16;
            if (!pending32.renameTo(getCacheFile(context, false))) return STATUS_CACHE_UNUSABLE;
            if (hasFp16 && pending16.renameTo(getCacheFile(context, true))) return STATUS_OK;
            deleteQuietly(getCacheFile(context, true));
            return STATUS_OK;
        } finally {
            deleteQuietly(pending32);
            deleteQuietly(pending16);
        }
    }

    public static synchronized int installFrom(Context context, Uri uri) {
        if (uri == null) return STATUS_NOT_INSTALLED;

        File staged = new File(context.getCacheDir(), STORE_DIR + File.separator + STAGED_DLL);
        File parent = staged.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return STATUS_CACHE_UNUSABLE;
        }

        try {
            copyToFile(context, uri, staged);
        } catch (IOException e) {
            deleteQuietly(staged);
            Log.w(TAG, "Unable to stage the selected file", e);
            return STATUS_UNREADABLE_FILE;
        }

        try {
            return installFrom(context, staged);
        } finally {
            deleteQuietly(staged);
        }
    }

    private static void copyToFile(Context context, Uri uri, File destination) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("openInputStream returned null");
            try (OutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
                output.flush();
            }
        }
    }

    private static boolean deleteQuietly(File file) {
        return file != null && file.isFile() && file.delete();
    }

    private static native int nativeBuildCache(String dllPath, String cachePath,
                                               boolean preferFp16);

    private static native int nativeInspectCache(String cachePath);

    private static native int nativeCacheVariant(String cachePath);

}
