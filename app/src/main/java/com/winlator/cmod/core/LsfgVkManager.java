package com.winlator.cmod.core;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.winlator.cmod.container.Container;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

public abstract class LsfgVkManager {
    private static final String ASSET_DLL = "lsfg_vk/Lossless.dll";
    private static final String ASSET_LIB = "lsfg_vk/android_arm64_v8a/liblsfg-vk-layer.so";
    private static final String ASSET_MANIFEST = "lsfg_vk/android_arm64_v8a/VkLayer_LS_frame_generation.json";
    private static final String CONFIG_RELATIVE_PATH = ".config/lsfg-vk/conf.toml";
    private static final String DLL_RELATIVE_DIR = ".local/share/lsfg-vk";
    public static final String EXTRA_ENABLED = "lsfgEnabled";
    public static final String EXTRA_FLOW_SCALE = "lsfgFlowScale";
    public static final String EXTRA_MULTIPLIER = "lsfgMultiplier";
    public static final String EXTRA_PERFORMANCE_MODE = "lsfgPerformanceMode";
    private static final String LAYER_RELATIVE_DIR = ".local/share/vulkan/implicit_layer.d";
    private static final String LIB_FILENAME = "liblsfg-vk-layer.so";
    private static final String LIB_RELATIVE_DIR = ".local/lib";
    private static final String LOSSLESS_DLL_NAME = "Lossless.dll";
    private static final String MANIFEST_FILENAME = "VkLayer_LS_frame_generation.json";
    private static final String PROCESS_EXE_IDENTIFIER = "winlator-lsfg";
    private static final String RUNTIME_VERSION = "v1.4.0-android-arm64-v8a-ahb-no-props";
    private static final String TAG = "LsfgVkManager";
    private static final String VERSION_FILENAME = ".lsfg_vk_runtime_version";

    public static boolean isGlobalDllAvailable(Context context) {
        File dllFile = globalDllFile(context);
        return dllFile != null && dllFile.isFile() && dllFile.length() > 0;
    }

    public static boolean isBundledDllAvailable(Context context) {
        return bundledDllSize(context) > 0;
    }

    public static File globalDllFile(Context context) {
        if (context == null) return null;
        return new File(context.getFilesDir(), "lsfg-vk/Lossless.dll");
    }

    public static String globalDllPath(Context context) {
        File dllFile = globalDllFile(context);
        if (dllFile == null || !dllFile.isFile() || dllFile.length() <= 0) return null;
        return dllFile.getAbsolutePath();
    }

    public static boolean importGlobalLosslessDll(Context context, Uri uri) {
        if (context == null || uri == null) return false;
        File dst = globalDllFile(context);
        if (dst == null) return false;
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        return copyUriTo(context, uri, dst);
    }

    public static boolean importLosslessDll(Context context, Container container, Uri uri) {
        if (context == null || container == null || uri == null) return false;
        File dst = containerDllFile(container);
        if (dst == null) return false;
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        return copyUriTo(context, uri, dst);
    }

    private static boolean copyUriTo(Context context, Uri uri, File dst) {
        try {
            ContentResolver resolver = context.getContentResolver();
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in == null) return false;
                try (FileOutputStream out = new FileOutputStream(dst)) {
                    byte[] buffer = new byte[131072];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }
            FileUtils.chmod(dst, 420);
            Log.i(TAG, "Imported Lossless.dll to " + dst.getAbsolutePath() + " size=" + dst.length());
            return dst.isFile() && dst.length() > 0;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to import Lossless.dll", t);
            return false;
        }
    }

    public static boolean isEnabled(Container container) {
        return container != null && parseBool(container.getExtra(EXTRA_ENABLED, "false"));
    }

    public static boolean isArmed(Container container) {
        return isEnabled(container) && containerDllPath(container) != null;
    }

    public static int multiplier(Container container) {
        int value = parseInt(container != null ? container.getExtra(EXTRA_MULTIPLIER, "0") : "0", 0);
        if (value == 0) return 0;
        return Math.max(2, Math.min(4, value));
    }

    public static float flowScale(Container container) {
        float value = parseFloat(container != null ? container.getExtra(EXTRA_FLOW_SCALE, "0.80") : "0.80", 0.8f);
        return Math.max(0.25f, Math.min(1.0f, value));
    }

    public static boolean performanceMode(Container container) {
        return container == null || parseBool(container.getExtra(EXTRA_PERFORMANCE_MODE, "true"));
    }

    public static String containerDllPath(Container container) {
        if (container == null || container.getRootDir() == null) return null;
        File dllFile = new File(container.getRootDir(), ".local/share/lsfg-vk/Lossless.dll");
        if (dllFile.isFile()) return dllFile.getAbsolutePath();
        return null;
    }

    public static File containerDllFile(Container container) {
        if (container == null || container.getRootDir() == null) return null;
        return new File(container.getRootDir(), ".local/share/lsfg-vk/Lossless.dll");
    }

    public static boolean ensureRuntimeInstalled(Context context, Container container) {
        if (context == null || container == null || container.getRootDir() == null) return false;
        File rootDir = container.getRootDir();
        File localLibDir = new File(rootDir, LIB_RELATIVE_DIR);
        File layerDir = new File(rootDir, LAYER_RELATIVE_DIR);
        File dllDir = new File(rootDir, DLL_RELATIVE_DIR);
        File libFile = new File(localLibDir, LIB_FILENAME);
        File manifestFile = new File(layerDir, MANIFEST_FILENAME);
        File versionFile = new File(layerDir, VERSION_FILENAME);

        String installedVersion = versionFile.isFile() ? FileUtils.readString(versionFile).trim() : "";
        boolean needsInstall = !RUNTIME_VERSION.equals(installedVersion) || !libFile.isFile() || !manifestFile.isFile();
        boolean success = true;
        if (needsInstall) {
            try {
                localLibDir.mkdirs();
                layerDir.mkdirs();
                FileUtils.copy(context, ASSET_LIB, libFile);
                FileUtils.copy(context, ASSET_MANIFEST, manifestFile);
                FileUtils.writeString(versionFile, RUNTIME_VERSION);
                FileUtils.chmod(libFile, 0755);
                FileUtils.chmod(manifestFile, 420);
                FileUtils.chmod(versionFile, 420);
                success = libFile.isFile() && manifestFile.isFile();
            } catch (Throwable t) {
                Log.e(TAG, "Failed to install LSFG runtime", t);
                success = false;
            }
        }

        File globalDll = globalDllFile(context);
        File sourceDll = (globalDll != null && globalDll.isFile()) ? globalDll : null;
        File dllFile = new File(dllDir, LOSSLESS_DLL_NAME);

        if (sourceDll == null) {
            if (isBundledDllAvailable(context)) {
                try {
                    long bundledSize = bundledDllSize(context);
                    if (!dllFile.isFile() || dllFile.length() != bundledSize) {
                        dllDir.mkdirs();
                        FileUtils.copy(context, ASSET_DLL, dllFile);
                        FileUtils.chmod(dllFile, 420);
                    }
                    return success;
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to copy bundled Lossless.dll into container", t);
                    return false;
                }
            }
            return !isEnabled(container) && success;
        }

        try {
            if (!dllFile.isFile() || dllFile.length() != sourceDll.length()) {
                dllDir.mkdirs();
                FileUtils.copy(sourceDll, dllFile);
                FileUtils.chmod(dllFile, 420);
            }
            return success;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to copy Lossless.dll into container", t);
            return false;
        }
    }

    public static boolean writeConfig(Container container) {
        if (container == null || container.getRootDir() == null) return false;
        String dllPath = containerDllPath(container);
        boolean enabled = isEnabled(container) && dllPath != null;
        File configFile = configFile(container);
        File parent = configFile.getParentFile();
        if (parent != null) parent.mkdirs();
        boolean ok = FileUtils.writeString(configFile,
                buildConfigToml(dllPath, enabled, multiplier(container), flowScale(container), performanceMode(container)));
        if (ok) FileUtils.chmod(configFile, 420);
        return ok;
    }

    public static boolean applyLaunchEnv(Container container, EnvVars envVars) {
        if (container == null || envVars == null || container.getRootDir() == null) return false;
        envVars.remove("DISABLE_LSFG");
        envVars.remove("LSFG_CONFIG");
        envVars.remove("LSFG_PROCESS");
        String dllPath = containerDllPath(container);
        boolean armed = isEnabled(container) && dllPath != null;
        if (!armed) {
            disableLayerInContainer(container);
            envVars.put("DISABLE_LSFG", "1");
            return false;
        }
        File layerDir = new File(container.getRootDir(), LAYER_RELATIVE_DIR);
        File manifestFile = new File(layerDir, MANIFEST_FILENAME);
        if (!manifestFile.isFile()) return false;
        envVars.put("LSFG_CONFIG", configFile(container).getAbsolutePath());
        envVars.put("LSFG_PROCESS", PROCESS_EXE_IDENTIFIER);
        String currentLayerPath = envVars.get("VK_LAYER_PATH");
        String layerPath = layerDir.getAbsolutePath();
        envVars.put("VK_LAYER_PATH",
                (currentLayerPath == null || currentLayerPath.isEmpty()) ? layerPath : currentLayerPath + ":" + layerPath);
        Log.i(TAG, "LSFG armed with multiplier=" + multiplier(container));
        return true;
    }

    public static boolean updateConfigAtRuntime(Container container, boolean enabled, int multiplier, float flowScale, boolean performanceMode) {
        if (container == null || container.getRootDir() == null) return false;
        String dllPath = containerDllPath(container);
        File configFile = configFile(container);
        if (!configFile.isFile()) return false;
        int effectiveMultiplier = (!enabled || dllPath == null) ? 1 : Math.max(2, Math.min(4, multiplier));
        boolean perfMode = performanceMode && enabled;
        boolean ok = FileUtils.writeString(configFile, buildConfigToml(dllPath, true, effectiveMultiplier, flowScale, perfMode));
        if (ok) FileUtils.chmod(configFile, 420);
        return ok;
    }

    private static void disableLayerInContainer(Container container) {
        File manifest = new File(container.getRootDir(), ".local/share/vulkan/implicit_layer.d/VkLayer_LS_frame_generation.json");
        if (manifest.exists() && !manifest.delete()) {
            Log.w(TAG, "Failed to remove disabled LSFG manifest: " + manifest);
        }
    }

    private static long bundledDllSize(Context context) {
        if (context == null) return 0L;
        try (InputStream in = context.getAssets().open(ASSET_DLL)) {
            byte[] buffer = new byte[131072];
            long total = 0;
            int len;
            while ((len = in.read(buffer)) > 0) total += len;
            return total;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static File configFile(Container container) {
        return new File(container.getRootDir(), CONFIG_RELATIVE_PATH);
    }

    private static String buildConfigToml(String dllPath, boolean enabled, int multiplier, float flowScale, boolean performanceMode) {
        StringBuilder b = new StringBuilder();
        b.append("version = 1\n\n");
        b.append("[global]\n");
        if (dllPath != null && !dllPath.isEmpty()) {
            b.append("dll = ").append(tomlString(dllPath)).append('\n');
        }
        b.append("no_fp16 = false\n\n");
        if (enabled && dllPath != null && !dllPath.isEmpty()) {
            b.append("[[game]]\n");
            b.append("exe = ").append(tomlString(PROCESS_EXE_IDENTIFIER)).append('\n');
            b.append("multiplier = ").append(Math.max(1, Math.min(4, multiplier))).append('\n');
            b.append("flow_scale = ").append(String.format(Locale.US, "%.2f", Math.max(0.25f, Math.min(1.0f, flowScale)))).append('\n');
            b.append("performance_mode = ").append(performanceMode ? "true" : "false").append('\n');
            b.append("hdr_mode = false\n");
            b.append("experimental_present_mode = ").append(tomlString("fifo")).append('\n');
        }
        return b.toString();
    }

    private static String tomlString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean parseBool(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return fallback; }
    }

    private static float parseFloat(String value, float fallback) {
        try { return Float.parseFloat(value); } catch (NumberFormatException e) { return fallback; }
    }
}
