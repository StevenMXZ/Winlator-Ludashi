package com.winlator.cmod.storage

import com.winlator.cmod.core.ContainerSessionGate
import com.winlator.cmod.core.ShortcutNames
import com.winlator.cmod.stores.DownloadNotifications
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class StorageScannerTest {
    @Test fun scansEachFileOnceAndSeparatesGamesFromTheirContainer() {
        val base = Files.createTempDirectory("ludashi-storage").toFile()
        try {
            val prefix = base.resolve("container").apply { mkdirs() }
            val game = prefix.resolve("game").apply { mkdirs() }
            game.resolve("game.exe").writeBytes(ByteArray(19))
            prefix.resolve("prefix.dat").writeBytes(ByteArray(7))
            base.resolve("settings.json").writeBytes(ByteArray(3))
            val snapshot = StorageScanner.measure(listOf(base, prefix, game), listOf(
                StorageTarget(base, StorageCategory.OTHER, "data"), StorageTarget(prefix, StorageCategory.CONTAINERS, "container"), StorageTarget(game, StorageCategory.GAMES, "Game")))
            assertEquals(29, snapshot.total)
            assertEquals(19, snapshot.bytes(StorageCategory.GAMES))
            assertEquals(7, snapshot.bytes(StorageCategory.CONTAINERS))
            assertEquals(3, snapshot.bytes(StorageCategory.OTHER))
        } finally { base.deleteRecursively() }
    }
    @Test fun doesNotFollowDriveLinksIntoExternalStorage() {
        val base = Files.createTempDirectory("ludashi-prefix").toFile()
        val external = Files.createTempDirectory("ludashi-external").toFile()
        try {
            external.resolve("keep.dat").writeBytes(ByteArray(500))
            base.resolve("own.dat").writeBytes(ByteArray(2))
            Files.createSymbolicLink(base.resolve("drive-z").toPath(), external.toPath())
            assertEquals(2, StorageScanner.measure(listOf(base), listOf(StorageTarget(base, StorageCategory.CONTAINERS, "prefix"))).total)
        } finally { base.resolve("drive-z").delete(); base.deleteRecursively(); external.deleteRecursively() }
    }
    @Test fun hardLinkedFilesAreNotCountedTwice() {
        val base = Files.createTempDirectory("ludashi-hardlinks").toFile()
        try {
            val original = base.resolve("original").apply { writeBytes(ByteArray(13)) }
            Files.createLink(base.resolve("link").toPath(), original.toPath())
            assertEquals(13, StorageScanner.measure(listOf(base), listOf(StorageTarget(base, StorageCategory.SYSTEM, "runtime"))).total)
        } finally { base.deleteRecursively() }
    }
    @Test fun cacheCleanupKeepsRegisteredGamesAndNeverFollowsSymlinks() {
        val cache = Files.createTempDirectory("ludashi-cache").toFile()
        val external = Files.createTempDirectory("ludashi-private").toFile()
        try {
            val game = cache.resolve("game").apply { mkdirs() }
            game.resolve("save.dat").writeText("keep save")
            cache.resolve("art.png").writeText("cached")
            external.resolve("private.txt").writeText("keep private")
            Files.createSymbolicLink(cache.resolve("link").toPath(), external.toPath())
            StorageScanner.clear(cache, listOf(game))
            assertEquals("keep save", game.resolve("save.dat").readText())
            assertEquals("keep private", external.resolve("private.txt").readText())
            assertFalse(cache.resolve("art.png").exists())
            assertFalse(Files.exists(cache.resolve("link").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        } finally { cache.deleteRecursively(); external.deleteRecursively() }
    }
    @Test fun leavingTheStorageScreenCanCancelTraversalWithoutChangingFiles() {
        val base = Files.createTempDirectory("ludashi-storage-cancel").toFile()
        try {
            repeat(20) { base.resolve("file-$it").writeText("data") }
            var visits = 0
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                StorageScanner.measure(listOf(base), listOf(StorageTarget(base, StorageCategory.OTHER, "data"))) { if (++visits == 5) throw java.util.concurrent.CancellationException() }
            }
            assertEquals(20, base.listFiles()!!.size)
        } finally { base.deleteRecursively() }
    }
    @Test fun cleanupAndWineStartupCannotOverlap() {
        assertTrue(ContainerSessionGate.maintain())
        try { assertFalse(ContainerSessionGate.start(9911)); assertFalse(ContainerSessionGate.edit(9911)) }
        finally { ContainerSessionGate.maintained() }
        assertTrue(ContainerSessionGate.start(9911))
        try { assertFalse(ContainerSessionGate.maintain()) } finally { ContainerSessionGate.stop(9911) }
    }
    @Test fun componentTransfersProtectCleanupWithoutBlockingNormalWineStartup() {
        assertTrue(ContainerSessionGate.transfer())
        try {
            assertFalse(ContainerSessionGate.maintain())
            assertTrue(ContainerSessionGate.start(9912))
            ContainerSessionGate.stop(9912)
        } finally { ContainerSessionGate.transferred() }
        assertTrue(ContainerSessionGate.maintain())
        try { assertFalse(ContainerSessionGate.transfer()) } finally { ContainerSessionGate.maintained() }
    }
    @Test fun managedShortcutTitlesUseDesktopNameWithoutChangingLocalShortcutNaming() {
        assertEquals("Moonscars", ShortcutNames.resolve("GOG-2106173825", "Moonscars", true, ""))
        assertEquals("Game title", ShortcutNames.resolve("itch-0496", "Game title", true, ""))
        assertEquals("My alias", ShortcutNames.resolve("itch-0496", "Game title", true, "My alias"))
        assertEquals("Custom filename", ShortcutNames.resolve("Custom filename", "Old title", false, ""))
    }
    @Test fun notificationProgressHandlesLargeDownloadsWithoutOverflow() {
        assertEquals(500, DownloadNotifications.progress(8_000_000_000, 16_000_000_000))
        assertEquals(1000, DownloadNotifications.progress(20, 10))
        assertEquals(0, DownloadNotifications.progress(10, 0))
    }
}
