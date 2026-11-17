package com.winlator.cmod.stores

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal object StoreDownloadPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("store-download-options", 0)
    fun language(context: Context, store: String) = prefs(context).getString("$store-language", "app") ?: "app"
    fun locale(context: Context, store: String): Locale {
        val chosen = language(context, store)
        return if (chosen != "app") Locale.forLanguageTag(chosen) else AppCompatDelegate.getApplicationLocales()[0]
            ?: context.resources.configuration.locales[0] ?: Locale.ENGLISH
    }
    fun setLanguage(context: Context, store: String, value: String) { prefs(context).edit().putString("$store-language", value).apply() }
    fun limit(context: Context, store: String) = prefs(context).getLong("$store-limit", 0).coerceAtLeast(0)
    fun setLimit(context: Context, store: String, value: Long) { prefs(context).edit().putLong("$store-limit", value.coerceAtLeast(0)).apply() }
    fun folder(context: Context, store: String): File = prefs(context).getString("$store-folder", null)?.let(::File)
        ?: File(context.filesDir, if (store == "gog") "gog-downloads" else "store-downloads/$store")
    fun setFolder(context: Context, store: String, folder: File?) {
        check(!StoreSessions.paused && !busy(store)) { "Finish the download and exit the game first" }
        if (folder != null) {
            check(folder.isDirectory || folder.mkdirs()) { "Could not create the download directory" }
            val probe = File.createTempFile("ludashi-", ".tmp", folder)
            check(probe.delete()) { "The download directory is not writable" }
        }
        prefs(context).edit().putString("$store-folder", folder?.canonicalPath).apply()
    }
    fun busy(store: String) = if (store == "gog") com.winlator.cmod.gog.GogLibrary.state.value.busy else LudashiStore.find(store)?.let { StoreSessions.state(it).value.busy } == true
    fun languageCode(value: String): String {
        val normalized = value.lowercase(Locale.ROOT).replace('_', '-').trim()
        return when (normalized) {
            "brazilian", "portuguese-brazilian", "portuguese (brazil)", "br", "pt-br" -> "pt-br"
            "english" -> "en"; "portuguese" -> "pt"; "german" -> "de"; "french" -> "fr"; "spanish" -> "es"
            "russian" -> "ru"; "polish" -> "pl"; "italian" -> "it"; "japanese" -> "ja"; "korean" -> "ko"
            "chinese", "simplified chinese", "zh-cn" -> "zh-cn"; "traditional chinese", "zh-tw" -> "zh-tw"
            else -> normalized
        }
    }
    fun installerIndex(installers: List<JSONObject>, locale: Locale): Int {
        val full = languageCode(locale.toLanguageTag())
        val base = languageCode(locale.language)
        val codes = installers.map { languageCode(it.optString("language")) }
        return codes.indexOf(full).takeIf { it >= 0 } ?: codes.indexOf(base).takeIf { it >= 0 }
            ?: codes.indexOf("en").takeIf { it >= 0 } ?: 0
    }
}

internal class TransferMeter(private val clock: () -> Long = android.os.SystemClock::elapsedRealtime) {
    private var at = clock()
    private var bytes = 0L
    var speed = 0L; private set
    fun update(done: Long): Long {
        val now = clock(); val elapsed = now - at
        if (done < bytes) { at = now; bytes = done; speed = 0; return 0 }
        if (elapsed >= 250) {
            val sample = (done - bytes) * 1000 / elapsed
            speed = if (speed == 0L) sample else (speed * 2 + sample) / 3
            at = now; bytes = done
        }
        return speed
    }
}

internal class TransferPacer(private val limit: Long) {
    private val started = System.nanoTime()
    fun pace(done: Long, check: () -> Unit) {
        if (limit <= 0) return
        val expected = (done.toDouble() * 1_000_000_000.0 / limit).toLong()
        while (true) {
            check()
            val remaining = expected - (System.nanoTime() - started)
            if (remaining <= 0) return
            java.util.concurrent.locks.LockSupport.parkNanos(minOf(remaining, 50_000_000))
        }
    }
}
