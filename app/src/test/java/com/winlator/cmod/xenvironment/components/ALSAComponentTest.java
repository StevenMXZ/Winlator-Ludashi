package com.winlator.cmod.xenvironment.components;

import android.content.Context;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class ALSAComponentTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void installsAudioOverOldServerWithoutReplacingGraphics() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File root = temporary.newFolder("root");
        File oldModule = new File(root, "usr/lib/alsa-lib/libasound_module_pcm_android_aserver.so");
        oldModule.getParentFile().mkdirs();
        Files.write(oldModule.toPath(), new byte[]{1});
        File graphics = new File(root, "usr/lib/displayx_layer.so");
        Files.write(graphics.toPath(), new byte[]{9, 8, 7});
        ALSAComponent.install(context, root);
        File module = new File(root, "usr/lib/alsa-lib/libasound_module_pcm_android_alsa.so");
        assertTrue(module.length() > 0);
        assertFalse(oldModule.exists());
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(graphics.toPath()));
        assertFalse(new File(root, "usr/lib/alsa-lib/displayx_layer.so").exists());
        String config = new String(Files.readAllBytes(new File(root,
                "usr/etc/alsa/conf.d/android_alsa.conf").toPath()), StandardCharsets.UTF_8);
        assertTrue(config.contains("type android_alsa"));
        assertTrue(config.contains("pcm.!default"));
        // A missing config must be repaired even if the version marker and module remain.
        assertTrue(new File(root, "usr/etc/alsa/conf.d/android_alsa.conf").delete());
        ALSAComponent.install(context, root);
        assertTrue(new File(root, "usr/etc/alsa/conf.d/android_alsa.conf").length() > 0);
    }
}
