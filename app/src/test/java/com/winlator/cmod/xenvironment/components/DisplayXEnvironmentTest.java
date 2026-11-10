package com.winlator.cmod.xenvironment.components;

import android.content.Context;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.GPUInformation;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = DisplayXEnvironmentTest.GpuShadow.class)
public class DisplayXEnvironmentTest {
    @Implements(value = GPUInformation.class, isInAndroidSdk = false)
    public static class GpuShadow {
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static String getRenderer(String driver, Context context) { return "Test GPU"; }
    }
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Container container() throws Exception {
        Container container = new Container(1);
        container.setRootDir(temporary.newFolder());
        container.setUseDisplayX(true);
        container.setTrueDisplayX(true);
        container.setDisplayXPresentMode("fifo");
        container.setSurfaceFormat("rgba8");
        return container;
    }

    @Test public void bypassLayerAndFormatsAreEnabledForBothX11Servers() throws Exception {
        Container container = container();
        for (String nativeX11 : new String[]{"0", "1"}) {
            EnvVars env = new EnvVars(); env.put("WINLATOR_NATIVE_X11", nativeX11);
            GuestProgramLauncherComponent.configureDisplayXEnvironment(env, container, null);
            assertEquals("VK_LAYER_DISPLAYX_display_x", env.get("VK_INSTANCE_LAYERS"));
            assertEquals("rgba8", env.get("DISPLAYX_SURFACE_FORMAT"));
            assertEquals("fifo", env.get("DISPLAYX_PRESENT_MODE"));
            assertEquals(nativeX11, env.get("WINLATOR_NATIVE_X11"));
        }
    }

    @Test public void nativeDri3ModeRemovesOnlyTheBypassLayer() throws Exception {
        Container container = container(); container.setTrueDisplayX(false);
        EnvVars env = new EnvVars(); env.put("WINLATOR_NATIVE_X11", "1");
        env.put("VK_INSTANCE_LAYERS", "VK_LAYER_OTHER:VK_LAYER_DISPLAYX_display_x");
        GuestProgramLauncherComponent.configureDisplayXEnvironment(env, container, null);
        assertEquals("VK_LAYER_OTHER", env.get("VK_INSTANCE_LAYERS"));
        assertEquals("fifo", env.get("DISPLAYX_PRESENT_MODE"));
    }

    @Test public void nativeShortcutOverridesDoNotChangeTheContainer() throws Exception {
        Container container = container();
        Shortcut shortcut = new Shortcut(container, temporary.newFile("game.desktop"));
        shortcut.setDisplayXPresentMode("mailbox"); shortcut.setSurfaceFormat("bgra8");
        EnvVars env = new EnvVars(); env.put("WINLATOR_NATIVE_X11", "1");
        GuestProgramLauncherComponent.configureDisplayXEnvironment(env, container, shortcut);
        assertEquals("mailbox", env.get("DISPLAYX_PRESENT_MODE"));
        assertEquals("bgra8", env.get("DISPLAYX_SURFACE_FORMAT"));
        shortcut.setUseDisplayX(false);
        GuestProgramLauncherComponent.configureDisplayXEnvironment(env, container, shortcut);
        assertEquals("", env.get("DISPLAYX_PRESENT_MODE"));
        assertEquals("", env.get("DISPLAYX_SURFACE_FORMAT"));
        assertEquals("", env.get("VK_INSTANCE_LAYERS"));
        assertTrue(container.getUseDisplayX());
        assertEquals("fifo", container.getDisplayXPresentMode());
    }
}
