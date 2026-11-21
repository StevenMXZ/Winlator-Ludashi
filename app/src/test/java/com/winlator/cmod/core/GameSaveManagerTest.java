package com.winlator.cmod.core;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.DisplayXPresentModeTest;
import com.winlator.cmod.container.Shortcut;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = DisplayXPresentModeTest.GpuShadow.class)
public class GameSaveManagerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void customFoldersSurviveReloadAndRestoreProgramDataSaves() throws Exception {
        Shortcut shortcut = shortcut();
        File drive = new File(shortcut.container.getRootDir(), ".wine/drive_c");
        File source = new File(drive, "ProgramData/Game/Saves");
        assertTrue(source.mkdirs());
        File save = new File(source, "slot1.sav");
        byte[] contents = "test save".getBytes(StandardCharsets.UTF_8);
        Files.write(save.toPath(), contents);
        File destination = temporary.newFolder();
        GameSaveManager.setLocations(shortcut, "c:\\programdata\\game\\Saves", destination.getPath());

        Shortcut reloaded = new Shortcut(shortcut.container, shortcut.file);
        assertEquals("C:\\programdata\\game\\Saves", GameSaveManager.getSaveFolder(reloaded));
        assertEquals(new File(destination, shortcut.name), GameSaveManager.getGameDir(reloaded));
        GameSaveManager.BackupResult backup = GameSaveManager.backup(reloaded, true);
        assertTrue(backup.error, backup.ok);
        assertEquals(1, backup.fileCount);
        assertTrue(save.delete());
        assertTrue(GameSaveManager.restoreLatest(reloaded).ok);
        assertArrayEquals(contents, Files.readAllBytes(save.toPath()));
    }

    @Test public void legacyProfileArchiveStillRestoresWithCustomFolderConfigured() throws Exception {
        Shortcut shortcut = shortcut();
        GameSaveManager.setLocations(shortcut, "C:\\ProgramData\\Game", temporary.newFolder().getPath());
        File folder = GameSaveManager.getGameDir(shortcut);
        assertTrue(folder.mkdirs());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(new File(folder, "old.zip").toPath()))) {
            zip.putNextEntry(new ZipEntry("drive_c/users/olduser/Documents/Game/slot1.sav"));
            zip.write(42);
            zip.closeEntry();
        }
        assertEquals(1, GameSaveManager.restoreLatest(shortcut).fileCount);
        File restored = new File(shortcut.container.getRootDir(), ".wine/drive_c/users/xuser/Documents/Game/slot1.sav");
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(restored.toPath()));
    }

    @Test public void rejectsTraversalAndOtherDrives() {
        for (String path : new String[]{"C:\\", "D:\\Game", "C:\\ProgramData\\..\\Game", "/ProgramData/Game"}) {
            assertThrows(IOException.class, () -> GameSaveManager.normalizeSaveFolder(path));
        }
    }

    private Shortcut shortcut() throws IOException {
        Container container = new Container(1);
        container.setRootDir(temporary.newFolder());
        File file = new File(container.getRootDir(), "Game.desktop");
        Files.write(file.toPath(), "[Desktop Entry]\nName=Game\nExec=wine Game.exe\n".getBytes(StandardCharsets.UTF_8));
        return new Shortcut(container, file);
    }
}
