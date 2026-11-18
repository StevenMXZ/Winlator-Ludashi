package com.winlator.cmod.inputcontrols;

import android.view.InputDevice;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.InputDeviceBuilder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ExternalControllerTest {
    @Test
    public void rejectsXiaomiUinputEvenWhenAndroidReportsGamepadSources() {
        assertFalse(ExternalController.isGameController(gamepad("Uinput-xiaomi", false)));
        assertFalse(ExternalController.isGameController(gamepad("ui-input-xiaomi", false)));
    }

    @Test
    public void stillAcceptsPhysicalControllers() {
        assertTrue(ExternalController.isGameController(gamepad("Xbox Wireless Controller", true)));
        assertTrue(ExternalController.isGameController(gamepad("Built-in Gamepad", false)));
    }

    private static InputDevice gamepad(String name, boolean external) {
        return InputDeviceBuilder.newBuilder()
                .setId(1)
                .setName(name)
                .setExternal(external)
                .setSources(InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK)
                .build();
    }
}
