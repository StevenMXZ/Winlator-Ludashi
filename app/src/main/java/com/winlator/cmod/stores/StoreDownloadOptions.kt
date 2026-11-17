package com.winlator.cmod.stores

import android.content.Intent
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.steam.StoreSection
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable internal fun StoreDownloadOptions(store: String, title: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(StoreDownloadPreferences.folder(context, store).path) }
    var language by remember { mutableStateOf(StoreDownloadPreferences.language(context, store)) }
    var limit by remember { mutableLongStateOf(StoreDownloadPreferences.limit(context, store)) }
    var languageMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val path = com.winlator.cmod.core.FileUtils.getFilePathFromUri(context, uri)
                check(!path.isNullOrBlank()) { uiStr(R.string.steam_folder_unavailable) }
                StoreDownloadPreferences.setFolder(context, store, File(path))
            } }
            error = result.exceptionOrNull()?.message.orEmpty()
            folder = StoreDownloadPreferences.folder(context, store).path
        }
    }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (store == "gog") result.data?.getStringExtra("code")?.let { com.winlator.cmod.gog.GogLibrary.signIn(context, it) }
            else LudashiStore.find(store)?.let { selected -> StoreSessions.run(context, selected) { StoreSessions.loginFinished(context, selected) } }
        }
    }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    StoreSection {
        ListItem(headlineContent = { Text(uiStr(R.string.steam_store_location)) }, supportingContent = { Text(folder, style = MaterialTheme.typography.bodySmall) },
            leadingContent = { Icon(Icons.Outlined.FolderOpen, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) }, modifier = Modifier.clickable { folderDialog = true })
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        Box {
            ListItem(headlineContent = { Text(uiStr(R.string.store_download_language)) }, supportingContent = { Text(if (language == "app") uiStr(R.string.store_app_language) else Locale.forLanguageTag(language).getDisplayName(Locale.getDefault())) },
                leadingContent = { Icon(Icons.Outlined.Language, null) }, trailingContent = { Icon(Icons.Outlined.ExpandMore, null) }, modifier = Modifier.clickable { languageMenu = true })
            DropdownMenu(languageMenu, { languageMenu = false }) {
                listOf("app", "en", "pt-BR", "pt", "es", "fr", "de", "it", "pl", "ru", "ja", "ko", "zh-CN", "zh-TW").forEach { code ->
                    DropdownMenuItem(text = { Text(if (code == "app") uiStr(R.string.store_app_language) else Locale.forLanguageTag(code).getDisplayName(Locale.getDefault())) }, onClick = {
                        language = code; StoreDownloadPreferences.setLanguage(context, store, code); languageMenu = false
                    })
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        Box {
            ListItem(headlineContent = { Text(uiStr(R.string.steam_ui_download_speed)) }, supportingContent = { Text(if (limit == 0L) uiStr(R.string.store_unlimited) else "${limit / 1048576} MiB/s") },
                leadingContent = { Icon(Icons.Outlined.Speed, null) }, trailingContent = { Icon(Icons.Outlined.ExpandMore, null) }, modifier = Modifier.clickable { speedMenu = true })
            DropdownMenu(speedMenu, { speedMenu = false }) {
                listOf(0L, 2L, 5L, 10L, 25L).forEach { mib -> DropdownMenuItem(text = { Text(if (mib == 0L) uiStr(R.string.store_unlimited) else "$mib MiB/s") }, onClick = {
                    limit = mib * 1048576; StoreDownloadPreferences.setLimit(context, store, limit); speedMenu = false
                }) }
            }
        }
    }
    Text(uiStr(R.string.store_language_fallback), style = MaterialTheme.typography.bodySmall)
    StoreSection {
        ListItem(headlineContent = { Text(uiStr(R.string.store_sign_in, title)) }, leadingContent = { Icon(Icons.Outlined.Person, null) }, modifier = Modifier.clickable {
            val target = if (store == "gog") Intent(context, com.winlator.cmod.gog.GogLoginActivity::class.java) else Intent(context, StoreLoginActivity::class.java).putExtra("store", store)
            login.launch(target)
        })
        ListItem(headlineContent = { Text(uiStr(R.string.store_sign_out, title)) }, leadingContent = { Icon(Icons.Outlined.Logout, null) }, modifier = Modifier.clickable {
            if (StoreDownloadPreferences.busy(store)) error = uiStr(R.string.store_finish_download)
            else if (store == "gog") com.winlator.cmod.gog.GogLibrary.signOut(context) else LudashiStore.find(store)?.let { StoreSessions.signOut(context, it) }
        })
    }
    if (folderDialog) AlertDialog(onDismissRequest = { folderDialog = false }, title = { Text(uiStr(R.string.steam_store_location)) }, text = { Text(uiStr(R.string.store_download_folder_help)) },
        confirmButton = { TextButton(enabled = !StoreDownloadPreferences.busy(store) && !StoreSessions.paused, onClick = { folderDialog = false; picker.launch(null) }) { Text(uiStr(R.string.steam_choose_folder)) } },
        dismissButton = { TextButton(enabled = !StoreDownloadPreferences.busy(store) && !StoreSessions.paused, onClick = {
            error = runCatching { StoreDownloadPreferences.setFolder(context, store, null) }.exceptionOrNull()?.message.orEmpty()
            folder = StoreDownloadPreferences.folder(context, store).path; folderDialog = false
        }) { Text(uiStr(R.string.steam_default_folder)) } })
}
