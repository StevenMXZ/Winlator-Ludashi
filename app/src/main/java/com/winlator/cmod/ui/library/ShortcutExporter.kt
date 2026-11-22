package com.winlator.cmod.ui.library

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.winlator.cmod.R
import com.winlator.cmod.SettingsFragment
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.ui.uiStr
import java.io.File
import java.io.IOException

object ShortcutExporter {
    internal fun desktopContents(lines: List<String>, containerId: Int): String = buildString {
        for (line in lines) {
            if (!line.startsWith("container_id:")) appendLine(line)
        }
        appendLine("container_id:$containerId")
    }

    @JvmStatic fun export(context: Context, shortcut: Shortcut) {
        runCatching {
            val contents = desktopContents(shortcut.file.readLines(), shortcut.container.id)
            val uri = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("shortcuts_export_path_uri", null)
            val directory = if (uri != null) {
                val picked = DocumentFile.fromTreeUri(context, Uri.parse(uri))
                if (picked == null || !picked.canWrite()) throw IOException(uiStr(R.string.steam_folder_unavailable))
                FileUtils.getFilePathFromUri(context, picked.uri)?.takeUnless { it.isBlank() }?.let(::File)
                    ?: throw IOException(uiStr(R.string.steam_folder_unavailable))
            } else File(SettingsFragment.DEFAULT_SHORTCUT_EXPORT_PATH)
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException(uiStr(R.string.steam_folder_unavailable))
            File(directory, shortcut.file.name).apply { writeText(contents) }.path
        }.onSuccess {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(context, uiStr(R.string.ui_preset_export_failed, it.message.orEmpty()), Toast.LENGTH_LONG).show()
        }
    }
}
