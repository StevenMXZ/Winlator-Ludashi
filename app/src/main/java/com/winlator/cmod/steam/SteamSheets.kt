package com.winlator.cmod.steam

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.winlator.cmod.ui.theme.WinZTheme

object SteamSheets {
    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(context: Context, content: @Composable (() -> Unit) -> Unit) {
        val activity = context as? AppCompatActivity ?: return
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val view = ComposeView(activity)
        val dismiss = { root.removeView(view); Unit }
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.setContent { WinZTheme {
            ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) { content(dismiss) }
            }
        } }
    }
    fun menu(context: Context) = show(context) { dismiss ->
        Text("Steam", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        listOf("Manage Steam games" to Icons.Outlined.SportsEsports, "Manage downloads" to Icons.Outlined.Download).forEachIndexed { index, (title, icon) ->
            ListItem(headlineContent = { Text(title) }, leadingContent = { Icon(icon, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                modifier = Modifier.clickable { dismiss(); context.startActivity(Intent(context, SteamActivity::class.java).putExtra("steam_tab", index)) })
        }
    }
    fun game(context: Context, id: Int) {
        if (!SteamSettings.enabled(context)) return
        context.startForegroundService(Intent(context, SteamService::class.java))
        show(context) { dismiss ->
            val state = observeSteam()
            val item = remember(state.library, id) { SteamLibrary.items(context, state.library).find { appId -> SteamLibrary.appId(appId.shortcutPath) == id } }
            var favorite by remember { mutableStateOf(context.getSharedPreferences("steam-settings", 0).getBoolean("favorite-$id", false)) }
            Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                    update = { Glide.with(it).load("https://shared.steamstatic.com/store_item_assets/steam/apps/$id/library_600x900_2x.jpg").into(it) }, modifier = Modifier.size(52.dp, 78.dp))
                Text(item?.name ?: "Steam game", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            val installed = java.io.File(context.filesDir, "steam-library/$id.json").isFile
            ListItem(headlineContent = { Text(if (installed) "View game" else "Download Steam game") }, leadingContent = { Icon(Icons.Outlined.Download, null) },
                modifier = Modifier.clickable(enabled = installed || (state.signedIn && !state.downloading && !state.gameActive)) {
                    if (installed) { dismiss(); SteamLibrary.open(context, "steam://$id") } else SteamSession.install(id)
                })
            ListItem(headlineContent = { Text(if (favorite) "Unfavorite" else "Favorite") }, leadingContent = { Icon(if (favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, null) },
                modifier = Modifier.clickable {
                    favorite = !favorite; context.getSharedPreferences("steam-settings", 0).edit().putBoolean("favorite-$id", favorite).apply(); SteamGames.changed()
                })
            if (state.downloading && state.id == id) SteamDownloadPanel(state)
            else if (!state.signedIn) {
                Text(state.status, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                if (!state.restoring && !state.gameActive) TextButton(modifier = Modifier.padding(horizontal = 16.dp), onClick = {
                    if (state.savedAccount) SteamSession.reconnect() else { dismiss(); context.startActivity(Intent(context, SteamActivity::class.java)) }
                }) { Text(if (state.savedAccount) "Retry connection" else "Connect Steam") }
            }
        }
    }
}
