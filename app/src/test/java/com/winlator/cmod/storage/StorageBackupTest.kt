package com.winlator.cmod.storage

import org.apache.commons.compress.archivers.tar.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.nio.file.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class StorageBackupTest {
    private fun manifest(kind: String = "files") = JSONObject().put("format", "ludashi-data").put("version", 1)
        .put("roots", JSONArray().put(JSONObject().put("kind", kind).put("path", "contents"))).put("settings", JSONObject())
    private fun parse(bytes: ByteArray, stage: File): Pair<Map<Path, Int>, Map<Path, String>> {
        val modes = linkedMapOf<Path, Int>(); val links = linkedMapOf<Path, String>()
        StorageBackup.read(bytes.inputStream(), stage, modes, links)
        return modes to links
    }
    private fun archive(root: File, kind: String = "files"): ByteArray = ByteArrayOutputStream().use { out ->
        val meta = manifest(kind)
        StorageBackup.write(out, listOf(meta.getJSONArray("roots").getJSONObject(0) to root), meta)
        out.toByteArray()
    }
    @Test fun preservesComponentBytesExecutablePermissionsAndRelativeLinks() {
        val source = Files.createTempDirectory("ludashi-backup").toFile()
        val stage = Files.createTempDirectory("ludashi-restore").toFile()
        try {
            val exe = source.resolve("bin/runtime").apply { parentFile.mkdirs(); writeText("actual runtime bytes") }
            Files.setPosixFilePermissions(exe.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
            Files.createSymbolicLink(source.resolve("bin/link").toPath(), Paths.get("runtime"))
            val (modes, links) = parse(archive(source), stage)
            assertEquals("actual runtime bytes", stage.resolve("0/bin/runtime").readText())
            assertEquals(493, modes[stage.resolve("0/bin/runtime").toPath()])
            assertEquals("runtime", links[stage.resolve("0/bin/link").toPath()])
        } finally { source.deleteRecursively(); stage.deleteRecursively() }
    }
    @Test fun publicBackupExcludesContainersLogsAndPreviousArchives() {
        val source = Files.createTempDirectory("ludashi-public").toFile()
        val stage = Files.createTempDirectory("ludashi-restore").toFile()
        try {
            for (name in listOf("Containers/registry", "logs/log.txt", "Ludashi-backup-old.tar", "Games/game.exe", "Saves/game.zip", "Presets/preset.wbp")) {
                source.resolve(name).apply { parentFile.mkdirs(); writeText("data") }
            }
            parse(archive(source, "public"), stage)
            assertFalse(stage.resolve("0/Containers").exists())
            assertFalse(stage.resolve("0/logs").exists())
            assertFalse(stage.resolve("0/Ludashi-backup-old.tar").exists())
            assertTrue(stage.resolve("0/Games/game.exe").isFile)
            assertTrue(stage.resolve("0/Saves/game.zip").isFile)
            assertTrue(stage.resolve("0/Presets/preset.wbp").isFile)
        } finally { source.deleteRecursively(); stage.deleteRecursively() }
    }
    @Test fun selectedPublicCategoriesExcludeUnselectedData() {
        val source = Files.createTempDirectory("ludashi-selective").toFile()
        val stage = Files.createTempDirectory("ludashi-restore").toFile()
        try {
            listOf("Components/runtime.so", "Saves/game.zip", "Games/game.exe", "Presets/input.wbp", "Containers/registry").forEach {
                source.resolve(it).apply { parentFile.mkdirs(); writeText("data") }
            }
            val meta = manifest("public")
            meta.getJSONArray("roots").getJSONObject(0).put("parts", JSONArray().put("SAVES").put("SETTINGS"))
            val bytes = ByteArrayOutputStream().use { out ->
                StorageBackup.write(out, listOf(meta.getJSONArray("roots").getJSONObject(0) to source), meta)
                out.toByteArray()
            }
            parse(bytes, stage)
            assertTrue(stage.resolve("0/Saves/game.zip").isFile)
            assertTrue(stage.resolve("0/Presets/input.wbp").isFile)
            assertFalse(stage.resolve("0/Games").exists())
            assertFalse(stage.resolve("0/Components").exists())
            assertFalse(stage.resolve("0/Containers").exists())
        } finally { source.deleteRecursively(); stage.deleteRecursively() }
    }
    @Test fun backupNeverIncludesItsOwnOutputEvenWithACustomFilename() {
        val source = Files.createTempDirectory("ludashi-backup-output").toFile()
        val stage = Files.createTempDirectory("ludashi-restore").toFile()
        try {
            val output = source.resolve("my-copy.tar").apply { writeText("partial output") }
            source.resolve("game.exe").writeText("game")
            val meta = manifest("public")
            val bytes = ByteArrayOutputStream().use { out ->
                StorageBackup.write(out, listOf(meta.getJSONArray("roots").getJSONObject(0) to source), meta, destination = output.canonicalFile)
                out.toByteArray()
            }
            parse(bytes, stage)
            assertFalse(stage.resolve("0/my-copy.tar").exists())
            assertTrue(stage.resolve("0/game.exe").isFile)
        } finally { source.deleteRecursively(); stage.deleteRecursively() }
    }
    @Test fun corruptionCannotBeReportedAsCompleted() {
        val source = Files.createTempDirectory("ludashi-backup").toFile()
        val stage = Files.createTempDirectory("ludashi-restore").toFile()
        try {
            val content = "unique-game-bytes-for-backup-integrity"
            source.resolve("game.exe").writeText(content)
            val bytes = archive(source)
            val offset = bytes.indices.first { i -> i + content.length <= bytes.size && bytes.copyOfRange(i, i + content.length).contentEquals(content.toByteArray()) }
            bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
            assertThrows(IllegalArgumentException::class.java) { parse(bytes, stage) }
        } finally { source.deleteRecursively(); stage.deleteRecursively() }
    }
    private fun unfinished(name: String): ByteArray = ByteArrayOutputStream().use { out ->
        TarArchiveOutputStream(out).use { tar ->
            val header = manifest().toString().toByteArray()
            tar.putArchiveEntry(TarArchiveEntry("ludashi.json").apply { size = header.size.toLong() }); tar.write(header); tar.closeArchiveEntry()
            tar.putArchiveEntry(TarArchiveEntry(name).apply { size = 1 }); tar.write(byteArrayOf(1)); tar.closeArchiveEntry()
        }
        out.toByteArray()
    }
    @Test fun missingCompletionFooterAndPathsAcrossRootsAreRejected() {
        for (name in listOf("data/0/file", "data/0/../../escaped", "data/0/../1/escaped")) {
            val stage = Files.createTempDirectory("ludashi-invalid").toFile()
            try { assertThrows(IllegalArgumentException::class.java) { parse(unfinished(name), stage) } }
            finally { stage.deleteRecursively() }
        }
    }
    @Test fun refusesExistingDestinationSymlinks() {
        val base = Files.createTempDirectory("ludashi-member").toFile()
        val external = Files.createTempDirectory("ludashi-external").toFile()
        try {
            Files.createSymbolicLink(base.resolve("link").toPath(), external.toPath())
            assertThrows(IllegalArgumentException::class.java) { StorageBackup.member(base.toPath(), "link/overwrite") }
            assertThrows(IllegalArgumentException::class.java) { StorageBackup.member(base.toPath(), "/absolute") }
            assertThrows(IllegalArgumentException::class.java) { StorageBackup.member(base.toPath(), "../../escaped") }
        } finally { base.deleteRecursively(); external.deleteRecursively() }
    }
}
