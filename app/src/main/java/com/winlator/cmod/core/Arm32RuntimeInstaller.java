package com.winlator.cmod.core;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads and installs the separate ARMHF userspace used by the 32-bit
 * Android build. The Android APK itself remains an armeabi-v7a/Bionic app;
 * Box86 and Wine execute inside the ARMHF glibc root filesystem via PRoot.
 */
public final class Arm32RuntimeInstaller {
    private static final String TAG = "Arm32RuntimeInstaller";
    private static final String RELEASE_BASE =
            "https://github.com/sagidsaim001-jpg/Winlator-Ludashi-32bit/releases/download/arm32-runtime-v1.0.0/";
    private static final String ARCHIVE_NAME = "arm32-runtime-bookworm-armhf.tar.zst";
    private static final String CHECKSUM_NAME = ARCHIVE_NAME + ".sha256";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .callTimeout(30, TimeUnit.MINUTES)
            .build();

    public interface ProgressListener {
        void onProgress(int progress);
    }

    private Arm32RuntimeInstaller() {}

    public static boolean isInstalled(Context context) {
        File root = new File(ImageFs.find(context).getRootDir(), "armhf-rootfs");
        return new File(root, "lib/ld-linux-armhf.so.3").isFile()
                && new File(root, "usr/bin/box86").isFile()
                && new File(root, "usr/lib/proot-loader.so").isFile()
                && new File(root, "usr/local/bin/winlator-wine").isFile()
                && new File(root, "opt/wine-11.22-x86/bin/wine").isFile();
    }

    public static boolean install(Context context, ImageFs imageFs, ProgressListener listener) {
        File cacheDir = new File(context.getFilesDir(), "arm32-runtime-cache");
        File stageDir = new File(context.getFilesDir(), "arm32-runtime-stage");
        File runtimeRoot = new File(imageFs.getRootDir(), "armhf-rootfs");
        File archive = new File(cacheDir, ARCHIVE_NAME);
        File partial = new File(cacheDir, ARCHIVE_NAME + ".part");

        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            Log.e(TAG, "Unable to create runtime cache directory");
            return false;
        }

        try {
            notifyProgress(listener, 1);
            String expectedSha256 = downloadChecksum();
            if (expectedSha256 == null) {
                Log.e(TAG, "Could not retrieve runtime checksum");
                return false;
            }

            FileUtils.delete(partial);
            if (!downloadArchive(partial, expectedSha256, listener)) {
                FileUtils.delete(partial);
                return false;
            }
            if (archive.exists()) FileUtils.delete(archive);
            if (!partial.renameTo(archive)) {
                Log.e(TAG, "Unable to move downloaded runtime archive into cache");
                return false;
            }

            notifyProgress(listener, 55);
            FileUtils.delete(stageDir);
            if (!stageDir.mkdirs()) {
                Log.e(TAG, "Unable to create staging directory");
                return false;
            }

            boolean extracted = TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD, archive, stageDir);
            if (!extracted || !hasRequiredRuntimeFiles(stageDir)) {
                Log.e(TAG, "Runtime archive extraction or architecture validation failed");
                FileUtils.delete(stageDir);
                return false;
            }

            notifyProgress(listener, 90);
            if (runtimeRoot.exists() && !FileUtils.delete(runtimeRoot)) {
                Log.e(TAG, "Unable to replace previous ARMHF runtime");
                return false;
            }
            if (!stageDir.renameTo(runtimeRoot)) {
                Log.e(TAG, "Unable to install ARMHF root filesystem");
                return false;
            }

            makeExecutable(runtimeRoot, "usr/bin/box86");
            makeExecutable(runtimeRoot, "usr/lib/proot-loader.so");
            makeExecutable(runtimeRoot, "usr/local/bin/winlator-wine");
            makeExecutable(runtimeRoot, "opt/wine-11.22-x86/bin/wine");

            if (!isInstalled(context)) {
                Log.e(TAG, "Installed runtime is missing one or more required files");
                return false;
            }

            imageFs.createImgVersionFile(22);
            FileUtils.delete(stageDir);
            FileUtils.delete(archive);
            notifyProgress(listener, 100);
            return true;
        } catch (Exception error) {
            Log.e(TAG, "ARMHF runtime installation failed", error);
            FileUtils.delete(partial);
            return false;
        }
    }

    private static String downloadChecksum() throws Exception {
        Request request = new Request.Builder()
                .url(RELEASE_BASE + CHECKSUM_NAME)
                .header("Cache-Control", "no-cache")
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            String value = response.body().string().trim();
            if (value.isEmpty()) return null;
            String checksum = value.split("\\s+")[0].toLowerCase(Locale.ROOT);
            return checksum.matches("[0-9a-f]{64}") ? checksum : null;
        }
    }

    private static boolean downloadArchive(File destination, String expectedSha256,
                                            ProgressListener listener) throws Exception {
        Request request = new Request.Builder()
                .url(RELEASE_BASE + ARCHIVE_NAME)
                .header("Cache-Control", "no-cache")
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.e(TAG, "Runtime download returned HTTP " + response.code());
                return false;
            }

            ResponseBody body = response.body();
            if (body == null) return false;
            long totalBytes = body.contentLength();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[128 * 1024];
            long copied = 0;

            try (InputStream input = body.byteStream();
                 FileOutputStream output = new FileOutputStream(destination)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    copied += count;
                    if (totalBytes > 0) {
                        int progress = 2 + (int)Math.min(48L, copied * 48L / totalBytes);
                        notifyProgress(listener, progress);
                    }
                }
                output.getFD().sync();
            }

            String actual = toHex(digest.digest());
            if (!expectedSha256.equalsIgnoreCase(actual)) {
                Log.e(TAG, "ARMHF runtime checksum mismatch. Expected " +
                        expectedSha256 + ", received " + actual);
                FileUtils.delete(destination);
                return false;
            }
            return destination.isFile() && destination.length() > 0;
        }
    }

    private static boolean hasRequiredRuntimeFiles(File root) {
        return new File(root, "lib/ld-linux-armhf.so.3").isFile()
                && new File(root, "usr/bin/box86").isFile()
                && new File(root, "usr/lib/proot-loader.so").isFile()
                && new File(root, "usr/local/bin/winlator-wine").isFile()
                && new File(root, "opt/wine-11.22-x86/bin/wine").isFile();
    }

        private static void makeExecutable(File root, String relativePath) {
        File file = new File(root, relativePath);
        if (file.isFile()) FileUtils.chmod(file, 0755);
    }

    private static String toHex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) builder.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return builder.toString();
    }

    private static void notifyProgress(ProgressListener listener, int progress) {
        if (listener != null) listener.onProgress(Math.max(0, Math.min(100, progress)));
    }
}
