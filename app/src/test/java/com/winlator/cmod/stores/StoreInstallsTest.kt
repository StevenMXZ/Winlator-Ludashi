package com.winlator.cmod.stores

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class StoreInstallsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun deletingAnItchDownloadOnlyDeletesItsPartialFiles() {
        val root = temporary.newFolder()
        val game = StoreGame(LudashiStore.ITCH, "https://example.itch.io/game", "Test Game")
        val upload = JSONObject().put("id", "98").put("name", "windows.zip")
        val otherUpload = JSONObject().put("id", "99").put("name", "windows.zip")
        val partial = StoreTransfers.partialFolder(root, game, upload).apply { mkdirs() }
        val other = StoreTransfers.partialFolder(root, game, otherUpload).apply { mkdirs() }
        StoreTransfers.partialArchive(partial, upload).writeText("downloaded bytes")
        StoreTransfers.partialArchive(other, otherUpload).writeText("unrelated download")
        File(partial, ".validator").writeText("etag")
        StoreTransfers.removePartial(root, game, upload)
        assertFalse(partial.exists())
        assertTrue(StoreTransfers.partialArchive(other, otherUpload).isFile)
    }

    @Test fun aRecordWithMissingGameFilesIsNotAnInstallation() {
        val context = RuntimeEnvironment.getApplication()
        val store = LudashiStore.ITCH
        val payload = temporary.newFolder()
        val game = StoreGame(store, "https://example.itch.io/fail-test", "Test Game")
        val record = File(StoreSessions.records(context, store), "${game.key}.json")
        record.writeText(JSONObject().put("path", payload.path).put("game", game.json())
            .put("executable", "Game.exe").put("executables", JSONArray(listOf("Game.exe"))).toString())
        try {
            assertNull(StoreInstalls.installedRecord(context, store, game.key))
            File(payload, "Game.exe").writeText("MZ")
            assertNotNull(StoreInstalls.installedRecord(context, store, game.key))
        } finally { record.delete() }
    }

    @Test fun zipWithWindowsPathsPublishesTheExecutableToStaging() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ludashi-stores", 0).edit().putBoolean("itch-enabled", true).apply()
        val archive = temporary.newFile("game.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Build\\")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("Build\\Game.exe")); zip.write("MZ".toByteArray()); zip.closeEntry()
        }
        val staging = temporary.newFolder()
        StoreInstalls.extract(context, LudashiStore.ITCH, archive, staging)
        assertEquals("MZ", File(staging, "Build/Game.exe").readText())
    }
}
