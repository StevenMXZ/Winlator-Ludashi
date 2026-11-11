package com.winlator.cmod.container;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Portable shortcut settings stored in a regular .desktop file.
 * Only whitelisted [Extra Data] keys are transferable. No executable,
 * container identity, saved-game paths, or arbitrary desktop commands.
 */
public final class ShortcutPresetManager {
    private ShortcutPresetManager() {}

    private static final int MAX_BYTES = 256 * 1024;
    public static final String[] GROUPS = {
        "Graphics / Renderer", "DXVK / VKD3D", "Box64 / FEXCore",
        "Environment Variables", "Display / FPS", "Input Controls"
    };
    private static final String[][] KEYS = {
        {"graphicsDriver","graphicsDriverConfig","renderer","rendererNative","rendererDriver",
         "rendererPresentMode","rendererFilterMode","surfaceFormat","useDisplayX","trueDisplayX",
         "displayXPerformanceMode","displayXPresentAtRefreshRate","displayXPresentMode","displayXDynamicRange",
         "displayXPrecisePresentation","displayServer","blacklistedExtensions"},
        {"dxwrapper","dxwrapperConfig","wincomponents"},
        {"emulator","box64Version","box64Preset","fexcoreVersion","fexcorePreset","forceSsbs"},
        {"envVars","lc_all","autoMesaGlVersionOverride"},
        {"screenSize","fullscreenStretched","frameGen","frameGenMultiplier",
         "frameGenTargetRate","frameGenFlowScale","sharpnessEffect","sharpnessLevel",
         "sharpnessDenoise"},
        {"controlsProfile","inputType","exclusiveXInput","disableXinput",
         "enableRelativeMouse","disableMouse","simTouchScreen","syncCpuTopology","cpuList"}
    };

    public static Map<String, String> readExtra(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int count;
        while ((count = input.read(chunk)) != -1) {
            if (buffer.size() + count > MAX_BYTES) throw new IOException("Preset too large");
            buffer.write(chunk, 0, count);
        }
        String content = buffer.toString(StandardCharsets.UTF_8.name());
        if (!content.contains("[Desktop Entry]")) throw new IOException("Not a .desktop file");
        Map<String,String> extras = new LinkedHashMap<>();
        String section = "";
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed.substring(1, trimmed.length()-1);
                continue;
            }
            if (!"Extra Data".equals(section) || trimmed.startsWith("#")) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1);
            if (value.length() > 16384) continue;
            extras.put(key, value);
        }
        return extras;
    }

    public static Map<String,String> read(Context context, Uri uri) throws IOException {
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException("Cannot open preset");
            return readExtra(stream);
        }
    }

    public static Map<String,String> effectiveSettings(Shortcut shortcut) {
        Map<String,String> result = new LinkedHashMap<>();
        Container c = shortcut.container;
        // Resolve inherited settings, rather than exporting only explicit overrides.
        Map<String,String> inherited = new HashMap<>();
        inherited.put("screenSize", c.getScreenSize());
        inherited.put("graphicsDriver", c.getGraphicsDriver());
        inherited.put("graphicsDriverConfig", c.getGraphicsDriverConfig());
        inherited.put("dxwrapper", c.getDXWrapper());
        inherited.put("dxwrapperConfig", c.getDXWrapperConfig());
        inherited.put("emulator", c.getEmulator());
        inherited.put("box64Version", c.getBox64Version());
        inherited.put("box64Preset", c.getBox64Preset());
        inherited.put("fexcoreVersion", c.getFEXCoreVersion());
        inherited.put("fexcorePreset", c.getFEXCorePreset());
        inherited.put("envVars", c.getEnvVars());
        inherited.put("wincomponents", c.getWinComponents());
        inherited.put("lc_all", c.getLC_ALL());
        inherited.put("inputType", String.valueOf(c.getInputType()));
        inherited.put("cpuList", c.getCPUList(true));
        inherited.put("displayServer", c.getExtra("displayServer", "native"));
        for (int i=0;i<KEYS.length;i++) {
            for (String key: KEYS[i]) {
                String value = shortcut.getExtra(key, inherited.getOrDefault(key, ""));
                if (!value.isEmpty()) result.put(key,value);
            }
        }
        return result;
    }

    public static void writeSettings(Context context, Uri uri, Shortcut shortcut) throws IOException {
        Map<String,String> data = effectiveSettings(shortcut);
        StringBuilder output = new StringBuilder("[Desktop Entry]\nType=Application\nName=Game Settings\n");
        output.append("X-Winlator-Preset-Version=1\n\n[Extra Data]\n");
        for (Map.Entry<String,String> e: data.entrySet()) {
            // Values are single-line in the .desktop storage model.
            if (e.getValue().contains("\n") || e.getValue().contains("\r")) continue;
            output.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        try (OutputStream stream = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (stream == null) throw new IOException("Cannot write preset");
            stream.write(output.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void apply(Shortcut target, Map<String,String> values, boolean[] selected) {
        if (selected.length != KEYS.length) throw new IllegalArgumentException("Invalid groups");
        for (int i=0;i<KEYS.length;i++) {
            if (!selected[i]) continue;
            for (String key: KEYS[i]) {
                if (values.containsKey(key)) target.putExtra(key, values.get(key));
            }
        }
        target.saveData();
    }

    public static boolean[] allGroups() {
        return new boolean[]{true,true,true,true,true,true};
    }
}
