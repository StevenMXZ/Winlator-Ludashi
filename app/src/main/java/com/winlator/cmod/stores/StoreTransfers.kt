package com.winlator.cmod.stores

import android.content.Context
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

internal object StoreTransfers {
    val downloadLock = Mutex()
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
    @Synchronized fun restore(context: Context, store: LudashiStore) {
        val history = load(context, store)
        StoreSessions.state(store).update { it.copy(transfers = history + it.transfers) }
    }

    fun partialRoot(context: Context, game: StoreGame, record: JSONObject?): File =
        record?.optString("partialRoot")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: StoreDownloadPreferences.folder(context, game.store.id)

    fun partialFolder(root: File, game: StoreGame, upload: JSONObject): File {
        val parent = root.canonicalFile
        val folder = File(parent, "ludashi-partial-${game.store.id}-${game.key}-${storeHash(upload.getString("id")).take(16)}")
        require(folder.canonicalFile == folder && !java.nio.file.Files.isSymbolicLink(folder.toPath()))
        return folder
    }

    fun removePartial(root: File, game: StoreGame, upload: JSONObject) {
        val folder = partialFolder(root, game, upload)
        val archive = partialArchive(folder, upload)
        java.nio.file.Files.deleteIfExists(archive.toPath())
        java.nio.file.Files.deleteIfExists(File(folder, ".validator").toPath())
        java.nio.file.Files.deleteIfExists(folder.toPath())
    }

    fun partialArchive(folder: File, upload: JSONObject): File {
        val filename = upload.getString("name")
        require(filename.isNotBlank() && filename.none { it in "/\\\r\n\u0000" } && filename !in listOf(".", "..", ".validator"))
        return File(folder, filename).also { require(!java.nio.file.Files.isSymbolicLink(it.toPath())) }
    }

    suspend fun delete(context: Context, game: StoreGame) = downloadLock.withLock {
        val state = StoreSessions.state(game.store)
        val record = state.value.transfers[game.key] ?: return@withLock
        check(record.optString("status") != "completed")
        val upload = record.optString("upload").takeIf { it.isNotBlank() }?.let(::JSONObject)
        if (upload != null && record.has("partialRoot")) removePartial(partialRoot(context, game, record), game, upload)
        synchronized(this) {
            java.nio.file.Files.deleteIfExists(File(directory(context, game.store), "${game.key}.json").toPath())
            state.update { it.copy(transfers = it.transfers - game.key, error = "") }
        }
    }

    @Synchronized fun save(context: Context, game: StoreGame, status: String, done: Long, total: Long, container: Int, upload: String, partialRoot: File, error: String = "") {
        val record = JSONObject().put("game", game.json()).put("status", status).put("done", done).put("total", total)
            .put("container", container).put("upload", upload).put("error", error).put("updated", System.currentTimeMillis())
            .put("partialRoot", partialRoot.path)
        StoreSessions.state(game.store).update { it.copy(transfers = it.transfers + (game.key to record)) }
        runCatching { atomicStoreFile(File(directory(context, game.store), "${game.key}.json"), record.toString()) }
            .onFailure { android.util.Log.w("StoreTransfer", "Could not persist transfer history", it) }
    }
}
