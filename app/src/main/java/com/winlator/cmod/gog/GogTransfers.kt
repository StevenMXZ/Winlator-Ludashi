package com.winlator.cmod.gog

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.flow.update

internal object GogTransfers {
    private fun directory(context: Context) = File(context.filesDir, "gog-transfers").apply { mkdirs() }
    fun load(context: Context): Map<String, JSONObject> = directory(context).listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
        runCatching {
            val data = JSONObject(file.readText())
            require(GogGame.parse(data).id == file.nameWithoutExtension)
            if (data.optString("status") in listOf("preparing", "downloading", "installing")) data.put("status", "paused")
            file.nameWithoutExtension to data
        }.getOrNull()
    }.toMap()
    fun save(context: Context, game: GogGame, status: String, done: Long, total: Long, container: Int, installer: String, error: String = "") {
        val record = game.json().put("status", status).put("done", done).put("total", total).put("container", container)
            .put("installer", installer).put("error", error).put("updated", System.currentTimeMillis())
        GogLibrary.mutable.update { it.copy(transfers = it.transfers + (game.id to record)) }
        runCatching { GogLibrary.atomic(File(directory(context), "${game.id}.json"), record.toString()) }
            .onFailure { android.util.Log.w("GOG", "Could not persist download history", it) }
    }
    fun remove(context: Context, id: String) {
        require(id.toLongOrNull()?.let { it > 0 } == true)
        check(!GogLibrary.state.value.busy) { "Finish or pause the GOG download first" }
        val folder = File(com.winlator.cmod.stores.StoreDownloadPreferences.folder(context, "gog"), id)
        require(!java.nio.file.Files.isSymbolicLink(folder.toPath())) { "Unsafe download directory" }
        if (folder.exists()) {
            com.winlator.cmod.storage.StorageScanner.clear(folder)
            check(folder.delete()) { "Cannot delete GOG download files" }
        }
        val history = File(directory(context), "$id.json")
        check(!history.exists() || history.delete()) { "Cannot remove download history" }
        GogLibrary.mutable.update { it.copy(transfers = it.transfers - id, revision = it.revision + 1) }
    }
}
