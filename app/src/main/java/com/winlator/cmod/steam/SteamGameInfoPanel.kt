package com.winlator.cmod.steam

import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.R
import android.widget.ImageView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextOverflow
import com.bumptech.glide.Glide
import java.text.DateFormat
import java.util.Date

/** Compact summary; detailed lists are lazy and only composed when requested. */
@Composable fun SteamGameInfoPanel(id: Int) {
    val context = LocalContext.current
    val state = observeSteam()
    val data = rememberSteamGameSnapshot(context, id)
    val achievements = data.achievements
    var showAchievements by remember(id) { mutableStateOf(false) }
    var showInfo by remember(id) { mutableStateOf(false) }
    var profileRequested by remember(id) { mutableStateOf(false) }
    var achievementsRequested by remember(id) { mutableStateOf(false) }
    val profileBusy = SteamUserData.busy()
    LaunchedEffect(id, state.signedIn, state.libraryLoading, profileBusy, data.ready) {
        if (data.ready && state.signedIn && !state.libraryLoading && !state.gameActive && !profileBusy) {
            if (!profileRequested && data.play == null) { profileRequested = true; SteamUserData.refreshProfile(context) }
            else if (!achievementsRequested) { achievementsRequested = true; SteamUserData.refreshAchievements(context, id) }
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showInfo = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Info, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                Text(uiStr(R.string.steam_ui_game_info), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { showAchievements = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.EmojiEvents, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                Text(achievements?.let { "${it.count { a -> a.unlocked }} / ${it.size}" } ?: uiStr(R.string.steam_ui_achievements), maxLines = 1)
            }
        }
        achievements?.takeIf { it.isNotEmpty() }?.let { list ->
            LinearProgressIndicator(progress = { list.count { it.unlocked }.toFloat() / list.size }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false }, title = { Text(uiStr(R.string.steam_ui_game_info)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val info = data.info
            val associations = info.optJSONObject("common")?.optJSONObject("associations")
            for ((type, label) in listOf("developer" to uiStr(R.string.steam_ui_developer), "publisher" to uiStr(R.string.steam_ui_publisher))) {
                val names = associations?.keys()?.asSequence()?.mapNotNull { associations.optJSONObject(it) }
                    ?.filter { it.optString("type") == type }?.map { it.optString("name") }?.filter { it.isNotBlank() }?.toList().orEmpty()
                val value = names.joinToString(", ").ifBlank { info.optJSONObject("extended")?.optString(type).orEmpty() }
                if (value.isNotBlank()) Text("$label: $value")
            }
            val release = info.optJSONObject("common")?.optLong("steam_release_date") ?: 0L
            if (release > 0) Text(uiStr(R.string.steam_ui_release, DateFormat.getDateInstance().format(Date(release * 1000))))
            data.play?.let {
                Text(uiStr(R.string.steam_ui_playtime, "%.1f".format(it.optLong("playtimeForever") / 60.0)))
                val last = it.optLong("rtimeLastPlayed")
                if (last > 0) Text(uiStr(R.string.steam_ui_last_played, DateFormat.getDateInstance().format(Date(last * 1000))))
            }
        }
    }, confirmButton = { TextButton(onClick = { showInfo = false }) { Text(uiStr(R.string.ui_close)) } })
    if (showAchievements) Dialog(onDismissRequest = { showAchievements = false }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .8f).dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(uiStr(R.string.steam_ui_achievements), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(enabled = state.signedIn && !state.gameActive && !SteamUserData.busy("achievements-$id"), onClick = { SteamUserData.refreshAchievements(context, id, true) }) { Icon(Icons.Outlined.Refresh, uiStr(R.string.steam_ui_refresh_info)) }
                }
                if (achievements == null) Text(if (SteamUserData.busy("achievements-$id")) uiStr(R.string.steam_ui_loading_achievements) else uiStr(R.string.steam_ui_connect_achievements))
                else {
                    Text(uiStr(R.string.steam_ui_ach_count, achievements.count { it.unlocked }, achievements.size))
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(achievements, key = { it.key }) { achievement ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AndroidView(factory = { ImageView(it) }, update = { Glide.with(it).load(achievement.icon).into(it) }, onRelease = { Glide.with(it).clear(it) }, modifier = Modifier.size(40.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(achievement.title, style = MaterialTheme.typography.labelLarge)
                                    Text(achievement.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (achievement.unlockedAt > 0) Text(DateFormat.getDateInstance().format(Date(achievement.unlockedAt * 1000)), style = MaterialTheme.typography.labelSmall)
                                }
                                if (achievement.unlocked) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                TextButton(onClick = { showAchievements = false }, modifier = Modifier.align(Alignment.End)) { Text(uiStr(R.string.ui_close)) }
            }
        }
    }
}
