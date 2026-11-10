package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SsbsSettingsTest {
    @Test public void absentOrInvalidGameSettingInheritsContainer() {
        for (String value : new String[] {null, "", "invalid"}) {
            assertTrue(SsbsSettings.resolve(true, value));
            assertFalse(SsbsSettings.resolve(false, value));
        }
    }

    @Test public void gameOverrideWinsInBothDirections() {
        assertTrue(SsbsSettings.resolve(false, "1"));
        assertFalse(SsbsSettings.resolve(true, "0"));
    }
}
