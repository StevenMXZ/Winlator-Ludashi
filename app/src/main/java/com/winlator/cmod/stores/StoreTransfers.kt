package com.winlator.cmod.stores

import android.content.Context
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

internal object StoreTransfers {
    private fun directory(context: Context, store: LudashiStore) = File(StoreSessions.root(context, store), "transfers").apply { mkdirs() }
    fun load(context: Context, store: LudashiStore): Map<String, JSONObject> = directory(context, store).listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
        runCatching {
            val record = JSONObject(file.readText())
            val game = StoreGame.parse(record.getJSONObject("game"))
            require(game.store == store && game.key == file.nameWithoutExtension)
            if (record.optString("status") in listOf("preparing", "downloading", "installing")) record.put("status", "paused")
            file.nameWithoutExtension to record
        }.getOrNull()
    }.toMap()
    fun save(context: Context, game: StoreGame, status: String, done: Long, total: Long, container: Int, upload: String, error: String = "") {
        val record = JSONObject().put("game", game.json()).put("status", status).put("done", done).put("total", total)
            .put("container", container).put("upload", upload).put("error", error).put("updated", System.currentTimeMillis())
        StoreSessions.state(game.store).update { it.copy(transfers = it.transfers + (game.key to record)) }
        runCatching { atomicStoreFile(File(directory(context, game.store), "${game.key}.json"), record.toString()) }
            .onFailure { android.util.Log.w("StoreTransfer", "Could not persist transfer history", it) }
    }
}
