package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class OpenGLDriverDeviceTest {
    @Test public void detectsAdreno825WithoutMatchingOtherAdrenos() {
        assertTrue(OpenGLDriverDefaults.isAdreno825("Adreno (TM) 825", ""));
        assertTrue(OpenGLDriverDefaults.isAdreno825("", "SM8735"));
        assertTrue(OpenGLDriverDefaults.isAdreno825("Turnip Adreno 825", null));
        assertFalse(OpenGLDriverDefaults.isAdreno825("Adreno (TM) 619", "SM4350"));
        assertFalse(OpenGLDriverDefaults.isAdreno825("Adreno 8250", ""));
        assertFalse(OpenGLDriverDefaults.isAdreno825("Mali-G715", null));
    }
}
