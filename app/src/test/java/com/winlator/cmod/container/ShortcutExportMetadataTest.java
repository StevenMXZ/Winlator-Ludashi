package com.winlator.cmod.container;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = DisplayXPresentModeTest.GpuShadow.class)
public class ShortcutExportMetadataTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void editingAnExportedShortcutPreservesItsContainerId() throws Exception {
        Container container = new Container(1);
        container.setRootDir(temporary.newFolder());
        File file = temporary.newFile("export.desktop");
        Files.write(file.toPath(), ("[Desktop Entry]\nName=Game\nExec=wine Game.exe\n"
                + "\n[Extra Data]\nfoo=old\ncontainer_id:1\n").getBytes(StandardCharsets.UTF_8));
        Shortcut shortcut = new Shortcut(container, file);
        shortcut.putExtra("foo", "new");
        shortcut.saveData();
        assertEquals("new", new Shortcut(container, file).getExtra("foo"));
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("Exec=wine Game.exe\n"));
        assertTrue(text.endsWith("container_id:1\n"));
        assertEquals(1, text.split("container_id:", -1).length - 1);
    }
}
