package com.winlator.cmod.steam

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SteamInstallRecordTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun externalContentStillCreatesPrivateInstallationRecords() {
        val files = temporary.newFolder()
        val content = temporary.newFolder()
        File(content, "game.exe").writeText("downloaded")
        assertFalse(File(files, "steam-library").exists())
        SteamInstallRecord.write(files, 17470, JSONObject().put("ludashiInstallPath", content.path))
        val record = JSONObject(File(files, "steam-library/17470.json").readText())
        assertEquals(content.path, record.getString("ludashiInstallPath"))
        assertEquals("downloaded", File(content, "game.exe").readText())
        assertEquals(listOf("17470.json"), File(files, "steam-library").list()!!.toList())
    }
    @Test fun replacingRecordLeavesCompleteJsonAndNoTemporaryFiles() {
        val files = temporary.newFolder()
        SteamInstallRecord.write(files, 1, JSONObject().put("build", 1))
        SteamInstallRecord.write(files, 1, JSONObject().put("build", 2))
        assertEquals(2, JSONObject(File(files, "steam-library/1.json").readText()).getInt("build"))
        assertEquals(1, File(files, "steam-library").list()!!.size)
    }
    @Test fun invalidRecordDirectoryFailsWithoutChangingGameFiles() {
        val files = temporary.newFolder()
        File(files, "steam-library").writeText("not a directory")
        assertThrows(IllegalStateException::class.java) { SteamInstallRecord.directory(files) }
        assertEquals("not a directory", File(files, "steam-library").readText())
    }
}
