package com.winlator.cmod.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class AudioOutputSettingsTest {
    @Test public void defaultsKeepExistingEnvironmentAndRejectMalformedNumbers() {
        EnvVars env = new EnvVars("ALSA_BUFFER_SIZE=8192 PULSE_LATENCY_MSEC=45");
        AudioOutputSettings.apply(env, "alsa-oboe", "auto", "513", "20");
        assertEquals("8192", env.get("ALSA_BUFFER_SIZE"));
        assertFalse(env.has("ALSA_USE_SLES"));
        AudioOutputSettings.apply(env, "pulse-audio-gn", "opensles", "2048", "121");
        assertEquals("45", env.get("PULSE_LATENCY_MSEC"));
        assertEquals("1", env.get("PULSEAUDIO_API"));
        assertEquals("8192", env.get("ALSA_BUFFER_SIZE"));
        assertFalse(env.has("ALSA_USE_SLES"));
    }

    @Test public void writesOnlyTheSelectedDriverOptionsAndAllowsExplicitAAudio() {
        EnvVars env = new EnvVars();
        AudioOutputSettings.apply(env, "alsa-oboe", "opensles", "1536", "45");
        assertEquals("1", env.get("ALSA_USE_SLES"));
        assertEquals("1536", env.get("ALSA_BUFFER_SIZE"));
        assertFalse(env.has("PULSE_LATENCY_MSEC"));
        AudioOutputSettings.apply(env, "alsa-oboe", "aaudio", "65536", "auto");
        assertFalse(env.has("ALSA_USE_SLES"));
        assertEquals("65536", env.get("ALSA_BUFFER_SIZE"));

        EnvVars pulse = new EnvVars();
        AudioOutputSettings.apply(pulse, "pulse-audio-gn", "opensles", "2048", "17");
        assertEquals("17", pulse.get("PULSE_LATENCY_MSEC"));
        assertEquals("1", pulse.get("PULSEAUDIO_API"));
        assertFalse(pulse.has("ALSA_USE_SLES"));
        assertFalse(pulse.has("ALSA_BUFFER_SIZE"));
        AudioOutputSettings.apply(pulse, "pulse-audio-gn", "aaudio", "auto", "auto");
        assertEquals("0", pulse.get("PULSEAUDIO_API"));
    }
}
