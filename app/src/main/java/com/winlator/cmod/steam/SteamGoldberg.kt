package com.winlator.cmod.steam

import android.content.Context
import com.winlator.cmod.core.TarCompressorUtils
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest

/** Optional upstream Goldberg regular mode. Never modifies an original without keeping a backup. */
object SteamGoldberg {
    private fun root(context: Context) = File(context.filesDir, "steam-components/goldberg")
    @Synchronized fun ensureInstalled(context: Context) {
        if (File(root(context), "regular/x64/steam_api64.dll").isFile) return
        val catalog = JSONObject(readUrl("https://raw.githubusercontent.com/The412Banner/winlator-contents/main/goldberg.json").toString(Charsets.UTF_8))
        val url = catalog.getString("url")
        require(url.startsWith("https://github.com/The412Banner/winlator-contents/releases/download/"))
        val expected = catalog.getString("file_checksum")
        require(expected.matches(Regex("[a-fA-F0-9]{32}"))) { "Goldberg catalog has no valid checksum" }
        val archive = File(context.cacheDir, "goldberg-download.tzst")
        try {
            val connection = URL(url).openConnection().apply { connectTimeout = 15000; readTimeout = 60000 }
            connection.getInputStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
            val digest = MessageDigest.getInstance("MD5")
            archive.inputStream().use { input -> val buffer = ByteArray(65536); var count = input.read(buffer); while (count >= 0) { digest.update(buffer, 0, count); count = input.read(buffer) } }
            check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }.equals(expected, true)) { "Goldberg checksum mismatch" }
            root(context).mkdirs()
            check(TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, root(context), null)) { "Goldberg extraction failed" }
            check(File(root(context), "regular/x64/steam_api64.dll").isFile) { "Goldberg component layout is unsupported" }
        } finally { archive.delete() }
    }
    private fun readUrl(url: String): ByteArray = URL(url).openConnection().apply { connectTimeout = 15000; readTimeout = 15000 }.getInputStream().use { it.readBytes() }
    fun restore(game: File) {
        game.walkTopDown().filter { it.isFile && it.name.endsWith(".ludashi-original") }.forEach { backup ->
            backup.copyTo(File(backup.parentFile, backup.name.removeSuffix(".ludashi-original")), true)
        }
    }
    fun prepare(context: Context, game: File, id: Int) {
        ensureInstalled(context)
        val dlls = game.walkTopDown().filter { it.isFile && it.name.lowercase() in setOf("steam_api.dll", "steam_api64.dll") }.toList()
        check(dlls.isNotEmpty()) { "No Steam API DLL found for regular Goldberg mode. Use SteamLudashi or Raw for this game." }
        dlls.forEach { dll ->
            val backup = File(dll.parentFile, dll.name + ".ludashi-original")
            if (!backup.exists()) dll.copyTo(backup)
            val arch = if (dll.name.equals("steam_api64.dll", true)) "x64" else "x86"
            File(root(context), "regular/$arch/${dll.name.lowercase()}").copyTo(dll, true)
            File(dll.parentFile, "steam_appid.txt").writeText(id.toString())
        }
    }
}
