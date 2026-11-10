package com.winlator.cmod.core;

import org.json.JSONException;
import org.json.JSONObject;

/** Video compatibility switches are independent of installed Windows component DLLs. */
public final class VideoDecoderSettings {
    public static final String DEFAULT_DECODER = "gstreamer";
    private VideoDecoderSettings() {}

    public static String value(String text, String key, boolean environment) {
        if (text == null || text.isEmpty()) return null;
        for (String entry : text.split(environment ? "\\s+" : ",")) {
            int separator = entry.indexOf('=');
            if (separator == key.length() && entry.substring(0, separator).equals(key)) {
                String result = entry.substring(separator + 1).trim();
                return result.isEmpty() ? null : result;
            }
        }
        return null;
    }

    public static String normalizeDecoder(String value) {
        return "ffmpeg".equalsIgnoreCase(value) || "winedmo".equalsIgnoreCase(value)
                ? "ffmpeg" : DEFAULT_DECODER;
    }

    private static boolean present(String value) { return value != null && !value.trim().isEmpty(); }
    private static boolean enabled(String value) { return "1".equals(value) || "true".equalsIgnoreCase(value); }

    public static String decoder(String saved, String legacy, String environment, String inherited) {
        if (present(saved)) return normalizeDecoder(saved);
        if (present(legacy)) return normalizeDecoder(legacy);
        if (present(environment)) return enabled(environment) ? "ffmpeg" : DEFAULT_DECODER;
        return normalizeDecoder(inherited);
    }

    public static boolean software(String saved, String legacy, String environment, boolean inherited) {
        if (present(saved)) return enabled(saved);
        if (present(legacy)) return enabled(legacy);
        if (present(environment)) return enabled(environment);
        return inherited;
    }

    public static void apply(EnvVars environment, String decoder, boolean software) {
        if (normalizeDecoder(decoder).equals("ffmpeg")) environment.put("WINE_USE_DMO", "1");
        else environment.remove("WINE_USE_DMO");
        // Wine tests presence/nonempty here: setting "0" would still enable the workaround.
        if (software) environment.put("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", "1");
        else environment.remove("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER");
    }

    /** Preserve Pipetto's legacy keys before the component-DLL migration removes unknown keys. */
    public static void migrateLegacy(JSONObject data, boolean containerRoot) throws JSONException {
        String components = data.optString("wincomponents", data.optString("dxcomponents", ""));
        String decoder = value(components, "builtinDecoder", false);
        String software = value(components, "softwareDecoding", false);
        if (decoder == null && software == null) return;
        JSONObject target = containerRoot ? data.optJSONObject("extraData") : data;
        if (target == null) { target = new JSONObject(); data.put("extraData", target); }
        if (decoder != null && !target.has("builtinDecoder")) target.put("builtinDecoder", normalizeDecoder(decoder));
        if (software != null && !target.has("softwareDecoding")) target.put("softwareDecoding", enabled(software) ? "1" : "0");
    }
}
