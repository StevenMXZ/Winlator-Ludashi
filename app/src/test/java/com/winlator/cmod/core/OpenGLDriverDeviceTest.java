package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class OpenGLDriverDeviceTest {
    @Test public void removesOnlyLegacyAutomaticMesaVersion() {
        assertEquals("FD_MESA_DEBUG=hiprio,sysmem vblank_mode=0",
                OpenGLDriverDefaults.withoutAutomaticMesaOverride(
                        "FD_MESA_DEBUG=hiprio,sysmem MESA_GL_VERSION_OVERRIDE=3.3 vblank_mode=0", true));
        assertEquals("", OpenGLDriverDefaults.withoutAutomaticMesaOverride("MESA_GL_VERSION_OVERRIDE=3.3", true));
        assertEquals("MESA_GL_VERSION_OVERRIDE=3.3",
                OpenGLDriverDefaults.withoutAutomaticMesaOverride("MESA_GL_VERSION_OVERRIDE=3.3", false));
        assertEquals("MESA_GL_VERSION_OVERRIDE=4.5COMPAT",
                OpenGLDriverDefaults.withoutAutomaticMesaOverride("MESA_GL_VERSION_OVERRIDE=4.5COMPAT", true));
        assertEquals("vblank_mode=0", OpenGLDriverDefaults.withoutAutomaticMesaOverride("vblank_mode=0", true));
        assertNull(OpenGLDriverDefaults.withoutAutomaticMesaOverride(null, true));
    }

    @Test public void detectsAdreno825WithoutMatchingOtherAdrenos() {
        assertTrue(OpenGLDriverDefaults.isAdreno825("Adreno (TM) 825", ""));
        assertTrue(OpenGLDriverDefaults.isAdreno825("", "SM8735"));
        assertTrue(OpenGLDriverDefaults.isAdreno825("Turnip Adreno 825", null));
        assertFalse(OpenGLDriverDefaults.isAdreno825("Adreno (TM) 619", "SM4350"));
        assertFalse(OpenGLDriverDefaults.isAdreno825("Adreno 8250", ""));
        assertFalse(OpenGLDriverDefaults.isAdreno825("Mali-G715", null));
    }
}
