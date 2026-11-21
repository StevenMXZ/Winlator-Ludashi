package com.winlator.cmod.contents;

import com.winlator.cmod.core.FileUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class AdrenotoolsDriverRecoveryTest {
    @Test public void recreatesRemovedBundledDriverAndRejectsMissingLibraries() throws Exception {
        AdrenotoolsManager manager = new AdrenotoolsManager(RuntimeEnvironment.getApplication()) {
            @Override public boolean isFromResources(String id) {
                return "bundled-test".equals(id);
            }

            @Override public boolean extractDriverFromResources(String id) {
                File directory = new File(getDriverPath(id));
                if (!directory.mkdirs()) return false;
                FileUtils.writeString(new File(directory, "meta.json"), "{\"libraryName\":\"libvulkan.so\"}");
                try {
                    return new File(directory, "libvulkan.so").createNewFile();
                } catch (java.io.IOException error) {
                    return false;
                }
            }
        };
        assertEquals("", manager.ensureDriverLibrary("System"));
        assertEquals("", manager.ensureDriverLibrary("missing-import"));
        assertEquals("libvulkan.so", manager.ensureDriverLibrary("bundled-test"));
        File library = new File(manager.getDriverPath("bundled-test"), "libvulkan.so");
        assertTrue(library.delete());
        assertEquals("", manager.ensureDriverLibrary("bundled-test"));

        manager.removeDriver("bundled-test");
        assertFalse(new File(manager.getDriverPath("bundled-test")).exists());
        assertEquals("libvulkan.so", manager.ensureDriverLibrary("bundled-test"));
    }
}
