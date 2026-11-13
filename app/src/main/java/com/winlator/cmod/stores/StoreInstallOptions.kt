package com.winlator.cmod.stores

import android.content.Intent
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.winlator.cmod.R
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.gog.*
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

internal fun preferredStoreUpload(uploads: List<StoreUpload>, locale: Locale): Int {
    val full = locale.toLanguageTag().lowercase(Locale.ROOT)
    val base = locale.language.lowercase(Locale.ROOT)
    fun matches(upload: StoreUpload, code: String): Boolean = upload.metadata.optString("language").replace('_', '-').equals(code, true) ||
        Regex("(^|[_. -])${Regex.escape(code)}([_. -]|$)", RegexOption.IGNORE_CASE).containsMatchIn(upload.name)
    return uploads.indexOfFirst { matches(it, full) }.takeIf { it >= 0 } ?: uploads.indexOfFirst { matches(it, base) }.takeIf { it >= 0 }
        ?: uploads.indexOfFirst { matches(it, "en") || matches(it, "english") }.takeIf { it >= 0 } ?: 0
}

@Composable internal fun StoreInstallOptions(storeId: String, gameId: String, record: JSONObject) {
    val context = LocalContext.current
    val gog by GogLibrary.state.collectAsStateWithLifecycle()
    val extra = LudashiStore.find(storeId)
    val remote = extra?.let { StoreSessions.state(it).collectAsStateWithLifecycle().value }
    val signedIn = if (extra == null) gog.signedIn else remote?.signedIn == true
    val busy = if (extra == null) gog.busy else remote?.busy == true
    var loading by remember(storeId, gameId) { mutableStateOf(true) }
    var error by remember(storeId, gameId) { mutableStateOf("") }
    var downloads by remember(storeId, gameId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var containers by remember { mutableStateOf<List<com.winlator.cmod.container.Container>>(emptyList()) }
    var selected by remember(storeId, gameId) { mutableIntStateOf(0) }
    var container by remember { mutableIntStateOf(0) }
    var chooseDownload by remember { mutableStateOf(false) }
    var chooseContainer by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (extra == null) result.data?.getStringExtra("code")?.let { GogLibrary.signIn(context, it) }
            else StoreSessions.run(context, extra) { StoreSessions.loginFinished(context, extra) }
        }
    }
    val game = remember(record) { if (extra != null) StoreGame.parse(record.getJSONObject("game")) else null }
    LaunchedEffect(storeId, gameId, signedIn, retry) {
        loading = true; error = ""
        val result = withContext(Dispatchers.IO) { runCatching {
            val list = ContainerManager(context).containers
            val files = if (!signedIn) emptyList() else if (extra == null) GogApi.installers(GogApi.get(context, "https://api.gog.com/products/$gameId?expand=downloads"))
                else if (extra == LudashiStore.ITCH) ItchConnector.uploads(context, game!!).map { JSONObject().put("id", it.id).put("name", it.name).put("size", it.size).put("metadata", it.metadata) }
                else listOf(JSONObject().put("id", "current").put("name", uiStr(R.string.store_current_version)))
            list to files
        } }
        result.onSuccess { (list, files) ->
            containers = list; container = list.firstOrNull()?.id ?: 0; downloads = files
            val locale = StoreDownloadPreferences.locale(context, storeId)
            selected = if (extra == null) StoreDownloadPreferences.installerIndex(files, locale) else preferredStoreUpload(files.map { StoreUpload(it.getString("id"), it.getString("name"), it.optLong("size"), it.optJSONObject("metadata") ?: JSONObject()) }, locale)
        }.onFailure { error = it.message.orEmpty() }
        loading = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (busy) {
            if (extra == null && gog.activeId == gameId) GogDownloadPanel(gog)
            else if (extra != null && remote?.active == gameId) StoreTransferPanel(extra, remote)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { retry++ }) { Text(uiStr(R.string.store_retry_download)) }
        }
        if (!signedIn) Button(onClick = {
            login.launch(if (extra == null) Intent(context, GogLoginActivity::class.java) else Intent(context, StoreLoginActivity::class.java).putExtra("store", storeId))
        }) { Text(uiStr(R.string.store_sign_in, extra?.title ?: "GOG")) }
        if (signedIn && !loading && downloads.isEmpty() && error.isBlank()) Text(uiStr(R.string.store_no_windows))
        Box {
            OutlinedButton(enabled = !loading && !busy && downloads.isNotEmpty(), onClick = { chooseDownload = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Language, null); Spacer(Modifier.width(8.dp))
                Text(downloads.getOrNull(selected)?.let { if (extra == null) "${it.optString("language")} · ${it.optString("version")}" else it.optString("name") } ?: uiStr(R.string.store_choose_download))
            }
            DropdownMenu(chooseDownload, { chooseDownload = false }) {
                downloads.forEachIndexed { index, file -> DropdownMenuItem(text = { Text(if (extra == null) "${file.optString("language")} · ${file.optString("version")}" else file.optString("name")) }, onClick = { selected = index; chooseDownload = false }) }
            }
        }
        Box {
            OutlinedButton(enabled = !loading && !busy, onClick = { chooseContainer = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Dns, null); Spacer(Modifier.width(8.dp)); Text(containers.find { it.id == container }?.name ?: uiStr(R.string.gog_choose_container))
            }
            DropdownMenu(chooseContainer, { chooseContainer = false }) { containers.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { container = item.id; chooseContainer = false }) } }
        }
        Button(enabled = signedIn && !loading && !busy && !StoreSessions.paused && container > 0 && downloads.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
            if (extra == null) context.startForegroundService(Intent(context, GogDownloadService::class.java).putExtra("game", gameId).putExtra("container", container).putExtra("installer", downloads[selected].toString()))
            else context.startForegroundService(Intent(context, StoreDownloadService::class.java).putExtra("store", storeId).putExtra("game", game!!.json().toString()).putExtra("container", container).putExtra("upload", downloads[selected].toString()))
        }) { Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(8.dp)); Text(uiStr(R.string.gog_download_install)) }
    }
}
