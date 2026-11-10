package com.winlator.cmod;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.widget.SeekBar;
import com.winlator.cmod.ui.SidebarCleanupView;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = SidebarSessionStateTest.GpuShadow.class)
public class SidebarSessionStateTest {
    @Implements(value = GPUInformation.class, isInAndroidSdk = false)
    public static class GpuShadow {
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static String getRenderer(String driver, Context context) { return "Test GPU"; }
    }
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private XServerDisplayActivity activity(Container container) {
        XServerDisplayActivity activity = Robolectric.buildActivity(XServerDisplayActivity.class).get();
        activity.setTheme(R.style.AppTheme);
        ReflectionHelpers.setField(activity, "container", container);
        return activity;
    }

    @Test public void openingHudControlsDoesNotEnableNextBootAndFirstToggleSelectsModern() throws Exception {
        Container container = new Container(1);
        container.setRootDir(temporary.newFolder());
        XServerDisplayActivity activity = activity(container);
        LinearLayout root = new LinearLayout(activity);
        Switch master = new Switch(activity); master.setId(R.id.SWHudMaster); root.addView(master);
        Spinner style = new Spinner(activity); style.setId(R.id.SPHudStyle); root.addView(style);
        LinearLayout options = new LinearLayout(activity); options.setId(R.id.LLModernHudOptions); root.addView(options);
        SidebarCleanupView cleanup = new SidebarCleanupView(activity); root.addView(cleanup);
        activity.setContentView(root);
        ReflectionHelpers.callInstanceMethod(activity, "setupSidebarHudControls");
        ReflectionHelpers.callInstanceMethod(cleanup, "bindHudExtras");
        assertFalse(master.isChecked());
        assertEquals("", container.getExtra("hudMode"));
        assertEquals(0, (int) ReflectionHelpers.<Integer>callInstanceMethod(activity(container), "getHudMode"));
        assertEquals(1, style.getSelectedItemPosition());
        master.setChecked(true);
        assertEquals("2", container.getExtra("hudMode"));
        master.setChecked(false);
        assertEquals(0, (int) ReflectionHelpers.<Integer>callInstanceMethod(activity(container), "getHudMode"));
    }

    @Test public void disablingColorBoostIsSavedToTheSameShortcutAndSurvivesReload() throws Exception {
        Container container = new Container(1);
        container.setRootDir(temporary.newFolder());
        container.putExtra("graphicsFilterMode", "5");
        Shortcut shortcut = new Shortcut(container, temporary.newFile("game.desktop"));
        XServerDisplayActivity activity = activity(container);
        ReflectionHelpers.setField(activity, "shortcut", shortcut);
        LinearLayout root = new LinearLayout(activity);
        Switch enabled = new Switch(activity); enabled.setId(R.id.SWEnableFSR); root.addView(enabled);
        Spinner upscaler = new Spinner(activity); upscaler.setId(R.id.SPUpscalerMode);
        upscaler.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item,
                new String[]{"SGSR", "FSR", "Lanczos", "Color Boost"}));
        upscaler.setSelection(3); root.addView(upscaler);
        Spinner postFx = new Spinner(activity); postFx.setId(R.id.SPPostFXMode);
        postFx.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, new String[]{"None"}));
        root.addView(postFx);
        SeekBar sharpness = new SeekBar(activity, null); sharpness.setId(R.id.SBSharpness); root.addView(sharpness);
        activity.setContentView(root);
        ReflectionHelpers.setField(activity, "graphicsControlsReady", true);
        enabled.setChecked(true);
        ReflectionHelpers.callInstanceMethod(activity, "saveSidebarGraphicsState");
        assertEquals("5", shortcut.getExtra("graphicsFilterMode"));
        enabled.setChecked(false);
        ReflectionHelpers.callInstanceMethod(activity, "saveSidebarGraphicsState");
        assertEquals("0", shortcut.getExtra("graphicsFilterMode"));
        Shortcut reloaded = new Shortcut(container, shortcut.file);
        assertEquals("0", reloaded.getExtra("graphicsFilterMode"));
        XServerDisplayActivity nextBoot = activity(container);
        ReflectionHelpers.setField(nextBoot, "shortcut", reloaded);
        assertEquals("0", ReflectionHelpers.callInstanceMethod(nextBoot, "getGraphicsPreference",
                ReflectionHelpers.ClassParameter.from(String.class, "graphicsFilterMode")));
        assertEquals("5", container.getExtra("graphicsFilterMode"));
    }
}
