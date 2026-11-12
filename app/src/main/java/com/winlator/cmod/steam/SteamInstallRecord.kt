package com.winlator.cmod.steam

import java.io.File
import org.json.JSONObject

internal object SteamInstallRecord {
    fun directory(filesDir: File): File = File(filesDir, "steam-library").also {
        check(it.isDirectory || it.mkdirs()) { "Cannot create Steam installation records" }
    }

    fun write(filesDir: File, id: Int, record: JSONObject) {
        require(id > 0)
        val root = directory(filesDir)
        val temp = File.createTempFile(".$id-", ".tmp", root)
        try {
            temp.writeText(record.toString())
            check(temp.renameTo(File(root, "$id.json"))) { "Cannot save Steam installation record" }
        } finally {
            temp.delete()
        }
    }
}
