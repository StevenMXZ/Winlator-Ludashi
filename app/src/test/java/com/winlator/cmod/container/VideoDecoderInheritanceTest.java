package com.winlator.cmod.container;

import com.winlator.cmod.core.VideoDecoderSettings;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = DisplayXPresentModeTest.GpuShadow.class)
public class VideoDecoderInheritanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void gamesInheritThenCanDisableBothContainerWorkarounds() throws Exception {
        Container container = new Container(1);
        container.setRootDir(temporary.newFolder());
        container.setBuiltinDecoder("ffmpeg"); container.setSoftwareDecoding(true);
        Shortcut shortcut = new Shortcut(container, temporary.newFile("game.desktop"));
        assertEquals("ffmpeg", shortcut.getBuiltinDecoder()); assertTrue(shortcut.getSoftwareDecoding());
        shortcut.putExtra("wincomponents", Container.DEFAULT_WINCOMPONENTS);
        assertEquals("ffmpeg", shortcut.getBuiltinDecoder()); assertTrue(shortcut.getSoftwareDecoding());
        shortcut.setBuiltinDecoder("gstreamer"); shortcut.setSoftwareDecoding(false);
        assertEquals("gstreamer", shortcut.getBuiltinDecoder()); assertFalse(shortcut.getSoftwareDecoding());
        assertEquals("ffmpeg", container.getBuiltinDecoder()); assertTrue(container.getSoftwareDecoding());
    }

    @Test public void importingPipettoKeysMigratesBeforeDllComponentFiltering() throws Exception {
        Container container = new Container(1);
        container.loadData(new JSONObject().put("wincomponents",
                "direct3d=1,builtinDecoder=ffmpeg,softwareDecoding=1"));
        assertEquals("ffmpeg", container.getBuiltinDecoder()); assertTrue(container.getSoftwareDecoding());
        assertFalse(container.getWinComponents().contains("builtinDecoder"));
        assertFalse(container.getWinComponents().contains("softwareDecoding"));
        JSONObject saved = new JSONObject().put("wincomponents", "builtinDecoder=ffmpeg")
                .put("extraData", new JSONObject().put("builtinDecoder", "gstreamer"));
        VideoDecoderSettings.migrateLegacy(saved, true);
        assertEquals("gstreamer", saved.getJSONObject("extraData").getString("builtinDecoder"));
    }

    @Test public void realLegacyShortcutRetainsCodecSelectionAfterMigration() throws Exception {
        Container container = new Container(1); container.setRootDir(temporary.newFolder());
        File file = temporary.newFile("legacy.desktop");
        Files.write(file.toPath(), ("[Desktop Entry]\nExec=wine game.exe\n[Extra Data]\n" +
                "wincomponents=direct3d=1,builtinDecoder=ffmpeg,softwareDecoding=1\n").getBytes(StandardCharsets.UTF_8));
        Shortcut shortcut = new Shortcut(container, file);
        assertEquals("ffmpeg", shortcut.getBuiltinDecoder()); assertTrue(shortcut.getSoftwareDecoding());
        assertFalse(shortcut.getExtra("wincomponents").contains("builtinDecoder"));
    }

    @Test public void manualEnvironmentMigratesToTheNewUiUntilAnExplicitChoiceIsSaved() {
        Container container = new Container(1);
        container.setEnvVars("WINE_USE_DMO=1 WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER=1");
        assertEquals("ffmpeg", container.getBuiltinDecoder()); assertTrue(container.getSoftwareDecoding());
        container.setBuiltinDecoder("gstreamer"); container.setSoftwareDecoding(false);
        assertEquals("gstreamer", container.getBuiltinDecoder()); assertFalse(container.getSoftwareDecoding());
    }
}
