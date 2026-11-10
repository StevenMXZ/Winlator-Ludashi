package com.winlator.cmod.steam

import com.winlator.cmod.ui.uiStr

import com.winlator.cmod.R

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
import com.winlator.cmod.ui.theme.WinZOverlayTheme

object SteamSheets {
    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(context: Context, content: @Composable (() -> Unit) -> Unit) {
        val activity = context as? AppCompatActivity ?: return
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val view = ComposeView(activity)
        val dismiss = { root.removeView(view); Unit }
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.setContent { WinZOverlayTheme {
            ModalBottomSheet(onDismissRequest = dismiss, contentWindowInsets = { WindowInsets(0, 0, 0, 0) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                SteamDialogFullscreen()
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)).padding(bottom = 8.dp)) { content(dismiss) }
            }
        } }
    }
    fun menu(context: Context) {
        val activity = context as? AppCompatActivity ?: return
        val anchor = activity.findViewById<android.view.View>(11)
            ?: activity.findViewById(com.winlator.cmod.R.id.Toolbar) ?: return
        val coordinates = IntArray(2); anchor.getLocationInWindow(coordinates)
        val bounds = androidx.compose.ui.unit.IntRect(coordinates[0], coordinates[1], coordinates[0] + anchor.width, coordinates[1] + anchor.height)
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val view = ComposeView(activity)
        val dismiss = { root.removeView(view); Unit }
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.setContent { WinZOverlayTheme {
            androidx.compose.ui.window.Popup(popupPositionProvider = object : androidx.compose.ui.window.PopupPositionProvider {
                override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize, layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize): androidx.compose.ui.unit.IntOffset =
                    androidx.compose.ui.unit.IntOffset(bounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), bounds.bottom.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            }, onDismissRequest = dismiss, properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
                Surface(shape = MaterialTheme.shapes.medium, shadowElevation = 10.dp, tonalElevation = 3.dp) {
                    Column(Modifier.width(260.dp).padding(vertical = 8.dp)) { MenuEntries(context, dismiss) }
                }
            }
        } }
    }
    @Composable fun MenuEntries(context: Context, dismiss: () -> Unit) {
        listOf(uiStr(R.string.steam_ui_manage_games) to Icons.Outlined.SportsEsports, uiStr(R.string.steam_ui_manage_downloads) to Icons.Outlined.Download).forEachIndexed { index, (title, icon) ->
            DropdownMenuItem(text = { Text(title) }, leadingIcon = { Icon(icon, null) }, onClick = {
                dismiss(); context.startActivity(Intent(context, SteamActivity::class.java).putExtra("steam_tab", index))
            })
        }
    }
    fun game(context: Context, id: Int) {
        if (!SteamSettings.enabled(context)) return
        context.startForegroundService(Intent(context, SteamService::class.java))
        show(context) { dismiss ->
            val state = observeSteam()
            val apps = rememberSteamLibrary(context, state.library)
            val item = remember(apps, id) { apps.find { app -> SteamLibrary.appId(app.shortcutPath) == id } }
            var favorite by remember { mutableStateOf(context.getSharedPreferences("steam-settings", 0).getBoolean("favorite-$id", false)) }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                    update = { Glide.with(it).load("https://shared.steamstatic.com/store_item_assets/steam/apps/$id/library_600x900_2x.jpg").into(it) }, modifier = Modifier.size(32.dp, 48.dp))
                Text(item?.name ?: uiStr(R.string.steam_ui_game), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            val installed = java.io.File(context.filesDir, "steam-library/$id.json").isFile
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = installed || (state.signedIn && !state.downloading && !state.gameActive), onClick = {
                    if (installed) { dismiss(); SteamLibrary.open(context, "steam://$id") } else SteamSession.install(id)
                }) { Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (installed) uiStr(R.string.steam_ui_view_game) else uiStr(R.string.steam_ui_download)) }
                FilledTonalButton(modifier = Modifier.weight(1f), onClick = {
                    favorite = !favorite; context.getSharedPreferences("steam-settings", 0).edit().putBoolean("favorite-$id", favorite).apply(); SteamGames.changed()
                }) { Icon(if (favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (favorite) uiStr(R.string.steam_ui_unfavorite) else uiStr(R.string.steam_ui_favorite)) }
            }
            SteamStoreDetails(id, installed, state)
            if (state.downloading && state.id == id) SteamDownloadPanel(state)
            else if (!state.signedIn) {
                Text(steamStatusText(state.status), Modifier.padding(horizontal = 16.dp, vertical = 4.dp).heightIn(max = 48.dp), style = MaterialTheme.typography.bodySmall)
                if (!state.restoring && !state.gameActive) TextButton(modifier = Modifier.padding(horizontal = 16.dp), onClick = {
                    if (state.savedAccount) SteamSession.reconnect() else { dismiss(); context.startActivity(Intent(context, SteamActivity::class.java)) }
                }) { Text(if (state.savedAccount) uiStr(R.string.steam_ui_retry_connection) else uiStr(R.string.steam_ui_connect)) }
            }
        }
    }
}
