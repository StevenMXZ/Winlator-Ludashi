package com.winlator.cmod.inputcontrols;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class GamepadStartupTest {
    @Test public void virtualPadIsDetectedBeforeLayoutAndKeyboardProfilesStayInvisible() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        File file = ControlsProfile.getProfileFile(context, 90001);
        file.getParentFile().mkdirs();
        try {
            Files.write(file.toPath(), "{\"elements\":[{\"bindings\":[\"GAMEPAD_BUTTON_A\"]}]}".getBytes(StandardCharsets.UTF_8));
            ControlsProfile pad = new ControlsProfile(context, 90001);
            assertFalse(pad.isElementsLoaded());
            pad.prepareGamepad();
            assertTrue(pad.isVirtualGamepad());
            assertFalse(pad.isElementsLoaded());
            Files.write(file.toPath(), "{\"elements\":[{\"bindings\":[\"KEY_A\"]}]}".getBytes(StandardCharsets.UTF_8));
            pad.prepareGamepad();
            assertFalse(pad.isVirtualGamepad());
        } finally { file.delete(); }
    }
}
