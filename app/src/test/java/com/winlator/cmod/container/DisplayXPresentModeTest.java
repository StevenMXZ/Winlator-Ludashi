package com.winlator.cmod.container;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import com.winlator.cmod.core.GPUInformation;
import android.content.Context;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = DisplayXPresentModeTest.GpuShadow.class)
public class DisplayXPresentModeTest {
    @Implements(value = GPUInformation.class, isInAndroidSdk = false)
    public static class GpuShadow {
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static String getRenderer(String driver, Context context) { return "Test GPU"; }
    }
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void containerMigratesTheOldToggleAndKeepsNewModeIndependentOfVulkan() {
        Container container = new Container(1);
        assertEquals("auto", container.getDisplayXPresentMode());
        container.setDisplayXBackPressure(true);
        assertEquals("fifo", container.getDisplayXPresentMode());
        container.setRendererPresentMode("fifo");
        container.setDisplayXPresentMode("mailbox");
        assertEquals("fifo", container.getRendererPresentMode());
        assertEquals("mailbox", container.getDisplayXPresentMode());
        container.setDisplayXPresentMode("FIFO");
        assertEquals("fifo", container.getDisplayXPresentMode());
    }

    @Test public void gameInheritsTheContainerAndCanOverrideInBothDirections() throws Exception {
        Container container = new Container(1);
        container.setRootDir(temporary.newFolder());
        container.setDisplayXPresentMode("fifo");
        Shortcut game = new Shortcut(container, temporary.newFile("game.desktop"));
        assertEquals("fifo", game.getDisplayXPresentMode());
        game.setDisplayXPresentMode("mailbox");
        assertEquals("mailbox", game.getDisplayXPresentMode());
        container.setDisplayXPresentMode("mailbox");
        game.setDisplayXPresentMode("fifo");
        assertEquals("fifo", game.getDisplayXPresentMode());
    }

    @Test public void oldGameOverridesAndLegacyConfigArePreserved() throws Exception {
        Container container = new Container(1);
        container.setRootDir(temporary.newFolder());
        Shortcut game = new Shortcut(container, temporary.newFile("legacy.desktop"));
        game.setDisplayXBackPressure(true);
        assertEquals("fifo", game.getDisplayXPresentMode());
        game.putExtra("displayxConfig", "presentMode=mailbox");
        assertEquals("mailbox", game.getDisplayXPresentMode());
        game.setDisplayXPresentMode("fifo");
        assertEquals("fifo", game.getDisplayXPresentMode());
    }
    @Test public void newDefaultsAndLegacyBackendsMigrateWithoutDroppingOverrides() throws Exception {
        Container container = new Container(1);
        container.setRootDir(temporary.newFolder());
        assertEquals("auto", container.getDisplayXPresentMode());
        assertFalse(container.getDisplayXPresentAtRefreshRate());
        assertEquals("sdr", container.getDisplayXDynamicRange());
        container.putExtra("displayxConfig", "backend=displayx,presentMode=fifo,dynamicRange=hdr,presentRR=1");
        assertTrue(container.getTrueDisplayX());
        assertTrue(container.getDisplayXPresentAtRefreshRate());
        assertEquals("hdr", container.getDisplayXDynamicRange());
        Shortcut shortcut = new Shortcut(container, temporary.newFile("hdr.desktop"));
        assertEquals("hdr", shortcut.getDisplayXDynamicRange());
        shortcut.putExtra("displayxConfig", "backend=x11,presentMode=auto,dynamicRange=sdr");
        assertFalse(shortcut.getTrueDisplayX());
        assertEquals("auto", shortcut.getDisplayXPresentMode());
        assertEquals("sdr", shortcut.getDisplayXDynamicRange());
        shortcut.setTrueDisplayX(true);
        shortcut.setDisplayXDynamicRange("hdr");
        assertTrue(shortcut.getTrueDisplayX());
        assertEquals("hdr", shortcut.getDisplayXDynamicRange());
    }

}
