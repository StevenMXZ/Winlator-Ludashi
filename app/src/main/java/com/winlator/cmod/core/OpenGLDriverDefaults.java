package com.winlator.cmod.core;

import android.content.Context;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

public final class OpenGLDriverDefaults {
    private static final String INITIALIZED = "openGlDefaultInitialized";
    private static final String AUTO_MESA_OVERRIDE = "autoMesaGlVersionOverride";
    private static final String MESA_OVERRIDE = "MESA_GL_VERSION_OVERRIDE";

    private OpenGLDriverDefaults() {}

    public static boolean initialize(Context context, JSONObject data) {
        if (data == null) return false;

        try {
            JSONObject extraData = data.optJSONObject("extraData");
            if (extraData == null) extraData = new JSONObject();
            boolean migrated = "1".equals(extraData.optString(AUTO_MESA_OVERRIDE, "0"));
            if (migrated) {
                data.put("envVars", withoutAutomaticMesaOverride(
                        data.optString("envVars", Container.DEFAULT_ENV_VARS), true));
                extraData.put(AUTO_MESA_OVERRIDE, "0");
                data.put("extraData", extraData);
            }
            if ("1".equals(extraData.optString(INITIALIZED, "0"))) return migrated;

            String config = data.optString("graphicsDriverConfig", Container.DEFAULT_GRAPHICSDRIVERCONFIG);
            String selectedVersion = resolveDriverVersion(context, configValue(config, "version"));
            boolean freedreno = shouldUseFreedreno(context, selectedVersion);
            data.put("graphicsDriver", freedreno ? "freedreno" : Container.DEFAULT_GRAPHICS_DRIVER);
            data.put("graphicsDriverConfig", putConfigValue(config, "version", selectedVersion));
            if (!extraData.has("surfaceFormat")) {
                extraData.put("surfaceFormat",
                        "1".equals(extraData.optString("useDisplayX", "0")) ? "rgba8" : "bgra8");
            }
            extraData.put(INITIALIZED, "1");
            extraData.put(AUTO_MESA_OVERRIDE, "0");
            data.put("extraData", extraData);
            return true;
        } catch (JSONException error) {
            return false;
        }
    }

    public static boolean initialize(Context context, Container container) {
        if (container == null) return false;
        boolean migrated = migrateAutomaticMesaOverride(container);
        if ("1".equals(container.getExtra(INITIALIZED, "0"))) return migrated;

        String config = container.getGraphicsDriverConfig();
        String selectedVersion = resolveDriverVersion(context, configValue(config, "version"));
        boolean freedreno = shouldUseFreedreno(context, selectedVersion);
        container.setGraphicsDriver(freedreno ? "freedreno" : Container.DEFAULT_GRAPHICS_DRIVER);
        container.setGraphicsDriverConfig(putConfigValue(config, "version", selectedVersion));
        container.putExtra(INITIALIZED, "1");
        container.putExtra(AUTO_MESA_OVERRIDE, "0");
        container.saveData();
        return true;
    }

    // Only remove the legacy override if Ludashi marked it as automatic.
    public static String withoutAutomaticMesaOverride(String raw, boolean automatic) {
        if (!automatic || raw == null || raw.isEmpty()) return raw;
        EnvVars environment = new EnvVars(raw);
        if (!"3.3".equals(environment.get(MESA_OVERRIDE))) return raw;
        environment.remove(MESA_OVERRIDE);
        return environment.toString();
    }

    public static boolean migrateAutomaticMesaOverride(Container container) {
        if (container == null || !"1".equals(container.getExtra(AUTO_MESA_OVERRIDE, "0"))) return false;
        container.setEnvVars(withoutAutomaticMesaOverride(container.getEnvVars(), true));
        container.putExtra(AUTO_MESA_OVERRIDE, "0");
        container.saveData();
        return true;
    }

    public static boolean migrateAutomaticMesaOverride(Shortcut shortcut) {
        if (shortcut == null || !"1".equals(shortcut.getExtra(AUTO_MESA_OVERRIDE, "0"))) return false;
        String raw = shortcut.getExtra("envVars", null);
        if (raw != null) shortcut.putExtra("envVars", withoutAutomaticMesaOverride(raw, true));
        shortcut.putExtra(AUTO_MESA_OVERRIDE, "0");
        shortcut.saveData();
        return true;
    }

    public static boolean shouldUseFreedreno(Context context, String version) {
        if (!isTurnipDriver(version)) return false;
        String soc = android.os.Build.VERSION.SDK_INT >= 31 ? android.os.Build.SOC_MODEL : "";
        String renderer = "";
        try { renderer = GPUInformation.getRenderer(null, context); } catch (Throwable ignored) {}
        return !isAdreno825(renderer, soc);
    }

    static boolean isAdreno825(String renderer, String soc) {
        String gpu = renderer == null ? "" : renderer.toLowerCase(Locale.ROOT);
        String model = soc == null ? "" : soc.toLowerCase(Locale.ROOT);
        return model.contains("sm8735") || (gpu.contains("adreno") &&
                java.util.regex.Pattern.compile("(?<![0-9])825(?![0-9])").matcher(gpu).find());
    }

    public static boolean isTurnipDriver(String version) {
        String value = version == null ? "" : version.toLowerCase(Locale.ROOT);
        return value.contains("turnip") || value.startsWith("tu-") || value.startsWith("mesa-turnip");
    }

    private static String resolveDriverVersion(Context context, String configuredVersion) {
        String candidate = isTurnipDriver(configuredVersion)
                ? configuredVersion
                : DefaultVersion.WRAPPER_ADRENO;
        try {
            if (GPUInformation.isDriverSupported(candidate, context)) return candidate;
            if (!candidate.equals(DefaultVersion.WRAPPER_ADRENO) &&
                    GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, context))
                return DefaultVersion.WRAPPER_ADRENO;
        } catch (Throwable ignored) {}
        return DefaultVersion.WRAPPER;
    }

    private static String configValue(String config, String key) {
        if (config == null || config.isEmpty()) return "";
        String prefix = key + "=";
        for (String item : config.split(";", -1)) {
            if (item.startsWith(prefix)) return item.substring(prefix.length());
        }
        return "";
    }

    private static String putConfigValue(String config, String key, String value) {
        String source = config == null ? "" : config;
        String prefix = key + "=";
        String[] items = source.split(";", -1);
        StringBuilder result = new StringBuilder();
        boolean replaced = false;

        for (String item : items) {
            if (result.length() > 0) result.append(';');
            if (item.startsWith(prefix)) {
                result.append(prefix).append(value);
                replaced = true;
            } else {
                result.append(item);
            }
        }

        if (!replaced) {
            if (result.length() > 0 && result.charAt(result.length() - 1) != ';') result.append(';');
            result.append(prefix).append(value);
        }
        return result.toString();
    }
}
