package com.winlator.cmod.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class JoystickRegistryTest {
    @Test public void exclusiveOffDeletesEveryRuleEvenWithDirectInputEnabled() {
        String reg = JoystickRegistry.regFile(true, false);
        assertFalse(reg.contains("override"));
        assertFalse(reg.contains("disabled"));
        for (String name : JoystickRegistry.names()) assertTrue(reg.contains("\"" + name + "\"=-\r\n"));
    }
    @Test public void exclusiveKeepsTheExistingInputTypePolicy() {
        assertEquals("disabled", JoystickRegistry.value(false, true));
        assertEquals("override", JoystickRegistry.value(true, true));
        assertNull(JoystickRegistry.value(false, false));
    }
}
