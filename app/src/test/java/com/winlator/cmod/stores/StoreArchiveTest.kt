package com.winlator.cmod.stores

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class StoreArchiveTest {
    private fun archive(file: File, path: String) {
        ZipOutputStream(file.outputStream()).use { zip -> zip.putNextEntry(ZipEntry(path)); zip.write("MZ-game-data".toByteArray()); zip.closeEntry() }
    }
    @Test fun zipExtractionRequiresTheCompleteCentralDirectory() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("store-archive").toFile()
        StoreSessions.resumeForLibrary(); StoreSessions.fresh(LudashiStore.ITCH)
        try {
            val zip = File(root, "game.zip"); archive(zip, "game.exe")
            val output = File(root, "valid").apply { mkdirs() }
            StoreInstalls.extract(context, LudashiStore.ITCH, zip, output)
            assertEquals("MZ-game-data", File(output, "game.exe").readText())
            val truncated = File(root, "truncated.zip"); truncated.writeBytes(zip.readBytes().dropLast(22).toByteArray())
            assertThrows(Exception::class.java) { StoreInstalls.extract(context, LudashiStore.ITCH, truncated, File(root, "partial").apply { mkdirs() }) }
        } finally { root.deleteRecursively() }
    }
    @Test fun archivePathsCannotEscapeTheStagingDirectory() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("store-archive-path").toFile()
        StoreSessions.resumeForLibrary(); StoreSessions.fresh(LudashiStore.ITCH)
        try {
            val zip = File(root, "game.zip"); archive(zip, "../escaped.exe")
            assertThrows(IllegalArgumentException::class.java) { StoreInstalls.extract(context, LudashiStore.ITCH, zip, File(root, "game").apply { mkdirs() }) }
            assertFalse(File(root, "escaped.exe").exists())
        } finally { root.deleteRecursively() }
    }
}
