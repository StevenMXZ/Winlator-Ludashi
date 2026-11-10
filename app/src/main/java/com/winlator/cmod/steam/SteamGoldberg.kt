package com.winlator.cmod.steam

import android.content.Context
import com.winlator.cmod.core.TarCompressorUtils
import java.io.File
import java.net.URL
import java.security.MessageDigest

/** Optional Goldberg regular mode. Never modifies an original without keeping a backup. */
object SteamGoldberg {
    private fun root(context: Context) = File(context.filesDir, "steam-components/goldberg")
    private const val downloadUrl = "https://github.com/Other-backup/winlator-imagefs-v2/releases/download/ludashi-goldberg-v1/goldberg.tzst"
    private const val expectedSha256 = "5b4cefb163cec0388411d13277a1ebf3bedeee462436e78717ce31fafdfdbb66"

    @JvmOverloads @Synchronized fun ensureInstalled(context: Context, progress: ((String, Long, Long) -> Unit)? = null) {
        if (File(root(context), "regular/x64/steam_api64.dll").isFile &&
            File(root(context), "regular/x86/steam_api.dll").isFile) return
        val archive = File(context.cacheDir, "goldberg-download.tzst")
        try {
            val connection = URL(downloadUrl).openConnection().apply {
                connectTimeout = 15000
                readTimeout = 60000
            }
            val expected = connection.contentLengthLong
            var received = 0L
            progress?.invoke("download", 0L, expected)
            connection.getInputStream().use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        progress?.invoke("download", received, expected)
                    }
                }
            }
            progress?.invoke("verify", 0L, 0L)
            val digest = MessageDigest.getInstance("SHA-256")
            archive.inputStream().use { input ->
                val bytes = ByteArray(65536)
                while (true) {
                    val count = input.read(bytes)
                    if (count == -1) break
                    digest.update(bytes, 0, count)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                .equals(expectedSha256, true)) { "The downloaded Goldberg component failed integrity verification." }
            root(context).mkdirs()
            progress?.invoke("extract", 0L, 0L)
            check(TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, root(context), null)) {
                "Goldberg extraction failed."
            }
            check(File(root(context), "regular/x64/steam_api64.dll").isFile &&
                File(root(context), "regular/x86/steam_api.dll").isFile) {
                "Goldberg files are incomplete."
            }
        } catch (error: java.io.IOException) {
            throw IllegalStateException("Unable to download Goldberg from Other-backup. Check your connection and retry.", error)
        } finally {
            archive.delete()
        }
    }
    private val dllIndex = java.util.concurrent.ConcurrentHashMap<String, List<File>>()
    fun invalidate(game: File) { dllIndex.remove(game.canonicalPath) }
    private fun apiDlls(game: File): List<File> {
        val root = game.canonicalFile
        return dllIndex.getOrPut(root.path) {
            root.walkTopDown().onEnter { it.canonicalFile.toPath().startsWith(root.toPath()) }
                .filter { it.isFile && it.canonicalFile.toPath().startsWith(root.toPath()) }
                .map { if (it.name.endsWith(".ludashi-original")) File(it.parentFile, it.name.removeSuffix(".ludashi-original")) else it }
                .filter { it.name.lowercase() in setOf("steam_api.dll", "steam_api64.dll") }.distinctBy { it.path }.toList()
        }
    }
    fun settingsDirectories(game: File) = apiDlls(game).map { File(it.parentFile, "steam_settings") }.distinct()
    fun restore(game: File) {
        apiDlls(game).forEach { target ->
            File(target.parentFile, target.name + ".ludashi-original").takeIf { it.isFile }?.copyTo(target, true)
        }
    }
    @JvmOverloads fun prepare(context: Context, game: File, id: Int, progress: ((String, Long, Long) -> Unit)? = null) {
        val dlls = apiDlls(game).filter { it.isFile }
        check(dlls.isNotEmpty()) { "No Steam API DLL found for regular Goldberg mode. Use SteamLudashi or Raw for this game." }
        ensureInstalled(context, progress)
        progress?.invoke("prepare", 0L, 0L)
        dlls.forEach { dll ->
            val backup = File(dll.parentFile, dll.name + ".ludashi-original")
            if (!backup.exists()) dll.copyTo(backup)
            val arch = if (dll.name.equals("steam_api64.dll", true)) "x64" else "x86"
            File(root(context), "regular/$arch/${dll.name.lowercase()}").copyTo(dll, true)
            File(dll.parentFile, "steam_appid.txt").writeText(id.toString())
        }
    }
}
