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
                && new File(root, "usr/local/bin/winlator-wine").isFile()
                && new File(root, "opt/wine-11.22-x86/bin/wine").isFile();
    }

    public static boolean install(Context context, ImageFs imageFs, ProgressListener listener) {
        File imageRoot = imageFs.getRootDir();
        if (!imageRoot.isDirectory() && !imageRoot.mkdirs()) {
            Log.e(TAG, "Unable to create the ImageFS directory for the ARMHF runtime");
            return false;
        }
        File cacheDir = new File(context.getFilesDir(), "arm32-runtime-cache");
        File stageDir = new File(context.getFilesDir(), "arm32-runtime-stage");
        File runtimeRoot = new File(imageRoot, "armhf-rootfs");
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

    /**
     * Create a real Wine prefix before the normal Winlator UI modifies registry
     * files and dosdevices. The Android process remains ARMv7; PRoot enters the
     * isolated ARMHF guest and Box86 starts Wine's i386 wineboot.
     */
    public static boolean initializeWinePrefix(Context context, ImageFs imageFs, File containerDir) {
        if (android.os.Process.is64Bit()) return false;
        if (!isInstalled(context) || containerDir == null) return false;

        File prefix = new File(containerDir, ".wine");
        File systemReg = new File(prefix, "system.reg");
        File userReg = new File(prefix, "user.reg");
        File windowsDir = new File(prefix, "drive_c/windows");
        if (systemReg.isFile() && userReg.isFile() && windowsDir.isDirectory()) return true;
        if (!prefix.isDirectory() && !prefix.mkdirs()) return false;

        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File proot = new File(nativeDir, "libproot.so");
        File prootLoader32 = new File(nativeDir, "libproot-loader.so");
        if (!proot.isFile() || !prootLoader32.isFile()) {
            Log.e(TAG, "Cannot bootstrap Wine prefix: ARMv7 PRoot or loader is missing");
            return false;
        }
        FileUtils.chmod(proot, 0755);
        FileUtils.chmod(prootLoader32, 0755);

        File prootTmpDir = new File(imageFs.getTmpDir(), "proot-prefix-" + containerDir.getName());
        if (!prootTmpDir.isDirectory() && !prootTmpDir.mkdirs()) return false;
        File logFile = new File(prootTmpDir, "wineboot.log");
        String guestHome = "/home/xuser";

        String[] command = new String[] {
                proot.getAbsolutePath(),
                "-r", new File(imageFs.getRootDir(), "armhf-rootfs").getAbsolutePath(),
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", containerDir.getAbsolutePath() + ":" + guestHome,
                "-w", guestHome,
                "--kill-on-exit",
                "/usr/local/bin/winlator-wine",
                "wineboot", "-u"
        };

        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(containerDir)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile);
            java.util.Map<String, String> environment = builder.environment();
            environment.clear();
            environment.put("HOME", guestHome);
            environment.put("USER", ImageFs.USER);
            environment.put("LANG", "en_US.UTF-8");
            environment.put("WINEPREFIX", guestHome + "/.wine");
            environment.put("WINEDEBUG", "-all");
            environment.put("PROOT_LOADER_32", prootLoader32.getAbsolutePath());
            environment.put("PROOT_TMP_DIR", prootTmpDir.getAbsolutePath());
            environment.put("PROOT_NO_SECCOMP", "1");

            java.lang.Process process = builder.start();
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                Log.e(TAG, "Wine prefix bootstrap timed out for " + containerDir.getName());
                return false;
            }
            int exitCode = process.exitValue();
            boolean ready = exitCode == 0 && systemReg.isFile()
                    && userReg.isFile() && windowsDir.isDirectory();
            if (!ready) {
                Log.e(TAG, "Wine prefix bootstrap failed for " + containerDir.getName()
                        + " with exit code " + exitCode + "; log=" + logFile);
            }
            return ready;
        } catch (Exception error) {
            Log.e(TAG, "Unable to bootstrap ARM32 Wine prefix", error);
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
