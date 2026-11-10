package com.winlator.cmod.widget;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HudPreferencesTest {
    private Context context;
    private SharedPreferences prefs;

    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        prefs = context.getSharedPreferences(WinlatorHUD.PREFS, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
    }

    @Test public void editingBeforeFirstActivationDoesNotUndoDisabledMetricsDuringMigration() {
        WinlatorHUD.setOptionPreference(context, WinlatorHUD.SHOW_GPU_NAME, false);
        WinlatorHUD.setOptionPreference(context, WinlatorHUD.SHOW_BATTERY_TEMP, false);
        assertTrue(WinlatorHUD.isOptionEnabled(context, WinlatorHUD.SHOW_GPU_USAGE));
        assertFalse(WinlatorHUD.isOptionEnabled(context, WinlatorHUD.SHOW_GPU_NAME));
        assertFalse(WinlatorHUD.isOptionEnabled(context, WinlatorHUD.SHOW_BATTERY_TEMP));
        assertTrue(prefs.getBoolean("hud_show_v2", false));
    }

    @Test public void rapidIndependentTogglesPreserveTheLatestState() {
        WinlatorHUD.setOptionPreference(context, WinlatorHUD.SHOW_FPS, false);
        WinlatorHUD.setOptionPreference(context, WinlatorHUD.SHOW_RAM, false);
        WinlatorHUD.setOptionPreference(context, WinlatorHUD.SHOW_FPS, true);
        assertTrue(WinlatorHUD.isOptionEnabled(context, WinlatorHUD.SHOW_FPS));
        assertFalse(WinlatorHUD.isOptionEnabled(context, WinlatorHUD.SHOW_RAM));
        assertTrue(WinlatorHUD.isOptionEnabled(context, WinlatorHUD.SHOW_CPU_USAGE));
    }
}
