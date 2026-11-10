package com.winlator.cmod.ui.containers

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

internal data class StorageUsage(val driveC: Long, val cache: Long, val total: Long)

internal object ContainerStorageUsage {
    private data class Cached(val measuredAt: Long, val usage: StorageUsage)
    private val cache = ConcurrentHashMap<String, Cached>()

    // One bounded-to-container traversal; do not follow drive links into shared storage.
    fun measure(root: File): StorageUsage {
        val key = root.absolutePath
        val now = System.nanoTime()
        cache[key]?.takeIf { now - it.measuredAt < 30_000_000_000L }?.let { return it.usage }
        val base = root.toPath()
        if (!root.isDirectory || Files.isSymbolicLink(base)) return StorageUsage(0, 0, 0)
        val drive = base.resolve(".wine/drive_c")
        val temp = base.resolve(".cache")
        var driveBytes = 0L
        var cacheBytes = 0L
        var total = 0L
        try {
            Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) {
                        total += attrs.size()
                        if (file.startsWith(drive)) driveBytes += attrs.size()
                        if (file.startsWith(temp)) cacheBytes += attrs.size()
                    }
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, error: IOException) = FileVisitResult.CONTINUE
            })
        } catch (_: IOException) { }
        val usage = StorageUsage(driveBytes, cacheBytes, total)
        if (cache.size > 128) cache.clear()
        cache[key] = Cached(now, usage)
        return usage
    }
}
