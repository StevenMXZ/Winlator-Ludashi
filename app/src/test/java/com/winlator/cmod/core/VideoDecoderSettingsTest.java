package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoDecoderSettingsTest {
    @Test public void switchesApplyOnEveryLaunchAndDisabledMeansAbsent() {
        EnvVars environment = new EnvVars("BOX64_DYNAREC=1 WINE_USE_DMO=0 WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER=0");
        VideoDecoderSettings.apply(environment, "ffmpeg", true);
        assertEquals("1", environment.get("WINE_USE_DMO"));
        assertEquals("1", environment.get("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER"));
        VideoDecoderSettings.apply(environment, "gstreamer", false);
        assertFalse(environment.has("WINE_USE_DMO"));
        assertFalse(environment.has("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER"));
        assertEquals("1", environment.get("BOX64_DYNAREC"));
    }

    @Test public void legacySinglePairsAndManualEnvironmentAreReadWithoutStickyZero() {
        assertEquals("ffmpeg", VideoDecoderSettings.value("builtinDecoder=ffmpeg", "builtinDecoder", false));
        assertEquals("1", VideoDecoderSettings.value("A=2 WINE_USE_DMO=1 B=3", "WINE_USE_DMO", true));
        assertEquals("ffmpeg", VideoDecoderSettings.decoder(null, null, "1", "gstreamer"));
        assertEquals("gstreamer", VideoDecoderSettings.decoder(null, null, "0", "ffmpeg"));
        assertFalse(VideoDecoderSettings.software(null, null, "0", true));
    }
}
