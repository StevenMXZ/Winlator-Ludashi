package com.winlator.cmod.core;

/** Optional overrides for the audio backend used by the guest. */
public final class AudioOutputSettings {
    public static final String API = "alsaAudioApi";
    public static final String BUFFER = "alsaBufferSize";
    public static final String LATENCY = "pulseLatencyMs";
    public static final String AUTO = "auto";
    public static final String AAUDIO = "aaudio";
    public static final String OPENSL_ES = "opensles";
    public static final int BUFFER_STEP_BYTES = 512;
    public static final int MAX_BUFFER_BYTES = 65536;
    public static final int MAX_LATENCY_MS = 120;

    private AudioOutputSettings() {}

    public static void apply(EnvVars env, String driver, String api, String buffer, String latency) {
        if ("alsa-oboe".equals(driver)) {
            if (OPENSL_ES.equals(api)) env.put("ALSA_USE_SLES", "1");
            else if (AAUDIO.equals(api)) env.remove("ALSA_USE_SLES");

            int size = bufferSize(buffer);
            if (size > 0) env.put("ALSA_BUFFER_SIZE", size);
        } else if ("pulse-audio-gn".equals(driver)) {
            if (OPENSL_ES.equals(api)) env.put("PULSEAUDIO_API", "1");
            else if (AAUDIO.equals(api)) env.put("PULSEAUDIO_API", "0");
            int millis = latencyMs(latency);
            if (millis > 0) env.put("PULSE_LATENCY_MSEC", millis);
        }
    }

    public static int bufferSize(String value) {
        int size = positiveValue(value, MAX_BUFFER_BYTES);
        return size % BUFFER_STEP_BYTES == 0 ? size : 0;
    }

    public static int latencyMs(String value) {
        return positiveValue(value, MAX_LATENCY_MS);
    }

    private static int positiveValue(String value, int max) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 && parsed <= max ? parsed : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
