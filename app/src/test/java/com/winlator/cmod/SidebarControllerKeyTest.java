package com.winlator.cmod;

import android.view.InputDevice;
import android.view.KeyEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class SidebarControllerKeyTest {
    public static class SidebarActivity extends XServerDisplayActivity {
        int toggles;
        @Override public void onBackPressed() { toggles++; }
    }

    @Test public void controllerBackOpensOnceAndConsumesItsRelease() {
        SidebarActivity activity = Robolectric.buildActivity(SidebarActivity.class).get();
        assertTrue(activity.dispatchKeyEvent(key(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN, 0, 0,
                InputDevice.SOURCE_GAMEPAD)));
        assertTrue(activity.dispatchKeyEvent(key(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN, 1, 0,
                InputDevice.SOURCE_GAMEPAD)));
        assertTrue(activity.dispatchKeyEvent(key(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP, 0, 0,
                InputDevice.SOURCE_GAMEPAD)));
        assertEquals(1, activity.toggles);
    }

    @Test public void selectKeyboardBackAndFallbackEventsRemainAvailableToNormalDispatch() {
        assertFalse(sidebar(KeyEvent.KEYCODE_BUTTON_SELECT, 0, InputDevice.SOURCE_GAMEPAD));
        assertFalse(sidebar(KeyEvent.KEYCODE_BACK, 0, InputDevice.SOURCE_KEYBOARD));
        assertFalse(sidebar(KeyEvent.KEYCODE_BACK, KeyEvent.FLAG_FALLBACK, InputDevice.SOURCE_GAMEPAD));
        assertTrue(sidebar(KeyEvent.KEYCODE_BUTTON_MODE, 0, InputDevice.SOURCE_GAMEPAD));
        assertTrue(sidebar(KeyEvent.KEYCODE_HOME, 0, InputDevice.SOURCE_GAMEPAD));
    }

    private static boolean sidebar(int code, int flags, int source) {
        return XServerDisplayActivity.isSidebarKey(key(code, KeyEvent.ACTION_DOWN, 0, flags, source));
    }

    private static KeyEvent key(int code, int action, int repeats, int flags, int source) {
        return new KeyEvent(1, 1, action, code, repeats, 0, -1, 0, flags, source);
    }
}
