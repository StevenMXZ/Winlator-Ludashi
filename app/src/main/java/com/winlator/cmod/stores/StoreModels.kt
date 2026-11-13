package com.winlator.cmod.stores

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import com.winlator.cmod.R
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

enum class LudashiStore(val id: String, val title: String, val icon: Int, val bit: Int) {
    ITCH("itch", "itch.io", R.drawable.ic_itch, 8), AMAZON("amazon", "Amazon", R.drawable.ic_amazon, 16);
    fun enabled(context: Context): Boolean {
        revision.intValue
        return context.getSharedPreferences("ludashi-stores", 0).getBoolean("$id-enabled", true)
    }
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("ludashi-stores", 0).edit().putBoolean("$id-enabled", enabled).apply()
        if (!enabled) StoreSessions.cancel(this)
        revision.intValue++
        if (enabled) StoreSessions.open(context, this)
    }
    companion object {
        private val revision = mutableIntStateOf(0)
        fun find(id: String?) = entries.firstOrNull { it.id == id }
        fun fromPath(path: String) = entries.firstOrNull { path.startsWith("${it.id}://") }
    }
}

internal fun storeHash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
internal data class StoreGame(val store: LudashiStore, val id: String, val title: String, val image: String = "", val metadata: JSONObject = JSONObject()) {
    val key = storeHash(id).take(32)
    val uri = "${store.id}://${Uri.encode(id)}"
    fun json() = JSONObject().put("store", store.id).put("id", id).put("title", title).put("image", image).put("metadata", metadata)
    companion object {
        fun parse(json: JSONObject): StoreGame {
            val store = LudashiStore.find(json.getString("store")) ?: error("Unknown store")
            val id = json.getString("id")
            require(id.isNotBlank() && id.length < 4096)
            return StoreGame(store, id, json.getString("title"), json.optString("image"), json.optJSONObject("metadata") ?: JSONObject())
        }
    }
}
internal data class StoreUpload(val id: String, val name: String, val size: Long, val metadata: JSONObject = JSONObject())
internal data class StoreSnapshot(
    val games: List<StoreGame> = emptyList(), val transfers: Map<String, JSONObject> = emptyMap(), val installed: Map<String, JSONObject> = emptyMap(), val signedIn: Boolean = false,
    val loading: Boolean = false, val error: String = "", val busy: Boolean = false, val active: String = "", val stage: String = "",
    val done: Long = 0, val total: Long = 0, val revision: Int = 0
)
internal fun atomicStoreFile(file: File, content: String) {
    file.parentFile?.mkdirs()
    val pending = File(file.parentFile, file.name + ".pending")
    pending.writeText(content)
    check(pending.renameTo(file)) { "Could not save store data" }
}
internal fun safeStorePath(root: File, path: String): File {
    val normalized = path.replace('\\', '/')
    require(normalized.isNotBlank() && !normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized) &&
        normalized.split('/').none { it == ".." || it.isEmpty() } && '\u0000' !in normalized) { "Unsafe game file path" }
    return File(root, normalized).canonicalFile.also { require(it.toPath().startsWith(root.canonicalFile.toPath())) { "Game file escaped the installation directory" } }
}
