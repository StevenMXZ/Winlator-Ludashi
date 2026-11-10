package com.winlator.cmod.ui.library

import android.content.Context
import android.os.Environment
import com.winlator.cmod.container.Shortcut
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// What the game page shows as stat chips. Playtime and play count are written by
// XServerDisplayActivity into the "playtime_stats" preferences (keyed by the shortcut name); the
// last launch is the shortcut's own lastRunAt; the size is measured off the game's folder.
data class GameStats(
    val playtimeMillis: Long = 0L,
    val playCount: Int = 0,
    val lastPlayedMillis: Long = 0L,
    val sizeText: String? = null
) {
    val hasAny: Boolean
        get() = playtimeMillis > 0L || playCount > 0 || lastPlayedMillis > 0L || sizeText != null
}

/** A measured folder size as stored; fresh = no need to measure again. */
class CachedSize(val text: String?, val fresh: Boolean)

object GameStatsLoader {
    // Names of the folders an exe usually sits in below the real game folder
    // (Game/Binaries/Win64/Game.exe): the size is taken from above them.
    private val BINARY_DIRS = setOf("win64", "win32", "x64", "x86", "bin", "bin32", "bin64", "binaries", "shipping", "retail")
    private const val MAX_ENTRIES = 250_000
    private const val MAX_SCAN_MS = 4_000L
    // A game folder rarely changes size: a stored size is reused for a day, or until the folder's
    // own entries change (its modification time moves), e.g. after an update was copied in.
    private const val STALE_MS = 24 * 60 * 60 * 1000L
    private const val PREFS = "game_size_cache"

    // Cheap part (a few preference reads and one small file): safe to call off the main thread.
    fun load(context: Context, shortcut: Shortcut): GameStats {
        val prefs = context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE)
        val playtime = prefs.getLong(shortcut.name + "_playtime", 0L)
        val plays = prefs.getInt(shortcut.name + "_play_count", 0)
        // Read the file again: the Shortcut the screen holds was loaded before the last launch.
        val lastRun = runCatching {
            Shortcut(shortcut.container, shortcut.file).getExtra("lastRunAt", "0").toLongOrNull() ?: 0L
        }.getOrDefault(0L)
        return GameStats(playtime, plays, lastRun, null)
    }

    // The stored size of the game folder, without touching the folder's contents. Null when it was
    // never measured (or the folder can't be told apart from a broader place). Off the main thread.
    fun cachedSize(context: Context, shortcut: Shortcut): CachedSize? {
        val dir = gameFolder(shortcut) ?: return null
        if (isBroadFolder(shortcut, dir)) return CachedSize(null, true)
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(dir.path, null) ?: return null
        val parts = raw.split(';')
        if (parts.size < 3) return null
        val bytes = parts[0].toLongOrNull() ?: return null
        val truncated = parts[1] == "1"
        val measuredAt = parts[2].toLongOrNull() ?: return null
        val now = System.currentTimeMillis()
        val fresh = now - measuredAt < STALE_MS && dir.lastModified() <= measuredAt
        return CachedSize(textOf(bytes, truncated), fresh)
    }

    // Measures the game folder and stores the result: call it off the main thread. Null when the
    // folder can't be told apart from a place that holds more than the game (Downloads, a drive root, ...).
    fun sizeText(context: Context, shortcut: Shortcut): String? {
        val dir = gameFolder(shortcut) ?: return null
        if (isBroadFolder(shortcut, dir)) return null
        val measuredAt = System.currentTimeMillis()
        val (bytes, truncated) = folderSize(dir)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(dir.path, "$bytes;${if (truncated) "1" else "0"};$measuredAt")
            .apply()
        return textOf(bytes, truncated)
    }

    private fun textOf(bytes: Long, truncated: Boolean): String? =
        if (bytes > 0L) formatBinarySize(bytes) + if (truncated) "+" else "" else null

    private fun resolveExeFile(shortcut: Shortcut): File? {
        val path = shortcut.path?.replace('\\', '/')?.trim()?.trim('"') ?: return null
        if (path.startsWith("/")) return File(path).takeIf { it.isFile }
        if (path.length < 3 || path[1] != ':' || path[2] != '/') return null
        val drive = path.substring(0, 1).lowercase(Locale.ROOT)
        val relative = path.substring(3)
        shortcut.container?.let { container ->
            for (entry in container.drivesIterator()) {
                if (entry != null && entry.size >= 2 && entry[0]?.replace(":", "")?.trim()?.equals(drive, true) == true) {
                    File(entry[1], relative).takeIf { it.isFile }?.let { return it }
                }
            }
            if (drive == "c") File(container.rootDir, ".wine/drive_c/$relative").takeIf { it.isFile }?.let { return it }
        }
        val roots = when (drive) {
            "d" -> listOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Environment.getExternalStorageDirectory())
            "z" -> listOf(File("/"))
            else -> emptyList()
        }
        return roots.map { File(it, relative) }.firstOrNull { it.isFile }
    }

    private fun gameFolder(shortcut: Shortcut): File? {
        val exe = resolveExeFile(shortcut) ?: return null
        var dir = exe.parentFile ?: return null
        var up = 0
        while (up < 4 && dir.name.lowercase(Locale.ROOT) in BINARY_DIRS) {
            dir = dir.parentFile ?: break
            up++
        }
        return dir
    }

    private fun isBroadFolder(shortcut: Shortcut, dir: File): Boolean {
        val broad = ArrayList<File>()
        broad.add(Environment.getExternalStorageDirectory())
        broad.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        broad.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
        broad.add(Environment.getRootDirectory())
        shortcut.container?.let { container ->
            container.rootDir?.let { broad.add(File(it, ".wine/drive_c")) }
            for (entry in container.drivesIterator()) {
                if (entry != null && entry.size >= 2 && entry[1] != null) broad.add(File(entry[1]))
            }
        }
        val target = runCatching { dir.canonicalPath }.getOrDefault(dir.path)
        return broad.any { runCatching { it.canonicalPath }.getOrDefault(it.path) == target }
    }

    // One stat per entry (walkFileTree hands over the attributes) and links are not followed, so
    // nothing outside the folder is counted. Stops at MAX_ENTRIES or MAX_SCAN_MS ("+" shown).
    private fun folderSize(root: File): Pair<Long, Boolean> {
        var total = 0L
        var entries = 0
        var truncated = false
        val deadline = System.currentTimeMillis() + MAX_SCAN_MS
        fun tick(): FileVisitResult {
            if (++entries > MAX_ENTRIES || ((entries and 255) == 0 && System.currentTimeMillis() > deadline)) {
                truncated = true
                return FileVisitResult.TERMINATE
            }
            return FileVisitResult.CONTINUE
        }
        runCatching {
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = tick()
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return tick()
                }
                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        return total to truncated
    }
}

internal fun formatBinarySize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.size - 1) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

internal fun formatPlaytime(playtimeMillis: Long): String {
    val totalMinutes = (playtimeMillis / 60000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}

internal fun formatLastPlayed(lastPlayedMillis: Long): String {
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    val playedYear = Calendar.getInstance().apply { timeInMillis = lastPlayedMillis }.get(Calendar.YEAR)
    val pattern = if (playedYear == thisYear) "MMM d" else "MMM d, yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(lastPlayedMillis))
}
