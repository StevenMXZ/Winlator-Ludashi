package com.winlator.cmod.steam

import com.winlator.cmod.ui.uiStr

import com.winlator.cmod.R

import android.widget.ImageView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

@Composable fun SteamGameInfoPanel(id: Int) {
    val context = LocalContext.current
    val state = observeSteam()
    val data = rememberSteamGameSnapshot(context, id)
    val info = data.info
    val play = data.play
    val achievements = data.achievements
    var expanded by remember(id) { mutableStateOf(false) }
    var profileRequested by remember(id) { mutableStateOf(false) }
    var achievementsRequested by remember(id) { mutableStateOf(false) }
    val profileBusy = SteamUserData.busy()
    LaunchedEffect(id, state.signedIn, state.libraryLoading, profileBusy, profileRequested, achievementsRequested, data.ready) {
        if (data.ready && state.signedIn && !state.libraryLoading && !state.gameActive && !profileBusy) {
            if (!profileRequested && play == null) { profileRequested = true; SteamUserData.refreshProfile(context) }
            else if (!achievementsRequested) { achievementsRequested = true; SteamUserData.refreshAchievements(context, id) }
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiStr(R.string.steam_ui_game_info), style = MaterialTheme.typography.titleMedium)
            val associations = info.optJSONObject("common")?.optJSONObject("associations")
            for ((type, label) in listOf("developer" to uiStr(R.string.steam_ui_developer), "publisher" to uiStr(R.string.steam_ui_publisher))) {
                val names = associations?.keys()?.asSequence()?.mapNotNull { associations.optJSONObject(it) }
                    ?.filter { it.optString("type") == type }?.map { it.optString("name") }?.filter { it.isNotBlank() }?.toList().orEmpty()
                val value = names.joinToString(", ").ifBlank { info.optJSONObject("extended")?.optString(type).orEmpty() }
                if (value.isNotBlank()) Text("$label: $value", style = MaterialTheme.typography.bodySmall)
            }
            val release = info.optJSONObject("common")?.optLong("steam_release_date") ?: 0L
            if (release > 0) Text(uiStr(R.string.steam_ui_release, DateFormat.getDateInstance().format(Date(release * 1000))), style = MaterialTheme.typography.bodySmall)
            play?.let {
                Text(uiStr(R.string.steam_ui_playtime, "%.1f".format(it.optLong("playtimeForever") / 60.0)), style = MaterialTheme.typography.bodySmall)
                val last = it.optLong("rtimeLastPlayed")
                if (last > 0) Text(uiStr(R.string.steam_ui_last_played, DateFormat.getDateInstance().format(Date(last * 1000))), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.EmojiEvents, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text(uiStr(R.string.steam_ui_achievements), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(enabled = state.signedIn && !state.gameActive && !SteamUserData.busy("achievements-$id"), onClick = {
                    SteamUserData.refreshAchievements(context, id, true)
                    SteamUserData.refreshProfile(context)
                }) { Icon(Icons.Outlined.Refresh, uiStr(R.string.steam_ui_refresh_info)) }
            }
            if (achievements != null) {
                val unlocked = achievements.count { it.unlocked }
                Text(uiStr(R.string.steam_ui_ach_count, unlocked, achievements.size), style = MaterialTheme.typography.bodySmall)
                if (achievements.isNotEmpty()) LinearProgressIndicator(progress = { unlocked.toFloat() / achievements.size }, modifier = Modifier.fillMaxWidth())
                achievements.take(if (expanded) achievements.size else 3).forEach { achievement ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AndroidView(factory = { ImageView(it) }, update = { Glide.with(it).load(achievement.icon).into(it) }, modifier = Modifier.size(40.dp))
                        Column(Modifier.weight(1f)) {
                            Text(achievement.title, style = MaterialTheme.typography.labelLarge)
                            Text(achievement.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (achievement.unlockedAt > 0) Text(DateFormat.getDateInstance().format(Date(achievement.unlockedAt * 1000)), style = MaterialTheme.typography.labelSmall)
                        }
                        if (achievement.unlocked) Icon(Icons.Outlined.EmojiEvents, uiStr(R.string.steam_ui_unlocked), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (achievements.size > 3) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) uiStr(R.string.steam_ui_show_less) else uiStr(R.string.steam_ui_view_achievements)) }
            } else {
                Text(if (SteamUserData.busy("achievements-$id")) uiStr(R.string.steam_ui_loading_achievements) else SteamUserData.error.ifBlank { if (state.signedIn) uiStr(R.string.steam_ui_refresh_achievements) else uiStr(R.string.steam_ui_connect_achievements) }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
