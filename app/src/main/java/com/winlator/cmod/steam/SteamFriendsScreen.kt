package com.winlator.cmod.steam

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.ui.theme.WinZTheme

class SteamFriendsActivity : ImmersiveSteamActivity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state); setContent { WinZTheme { SteamFriendsScreen { finish() } } } }
}
@Composable fun SteamFriendsScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val friends by SteamSocial.friends.collectAsState()
    val messages by SteamSocial.messages.collectAsState()
    val status by SteamSocial.status.collectAsState()
    var selected by remember { mutableLongStateOf(0L) }
    var draft by remember(selected) { mutableStateOf("") }
    LaunchedEffect(Unit) { SteamSocial.appEvent(context) }
    LaunchedEffect(selected) { if (selected != 0L) SteamSocial.history(context, selected) }
    Column(Modifier.fillMaxSize().imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            if (selected != 0L || onBack != null) IconButton(onClick = { if (selected != 0L) selected = 0L else onBack?.invoke() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiStr(R.string.ui_back)) }
            Text(friends.firstOrNull { it.id == selected }?.name ?: uiStr(R.string.steam_friends), Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.titleLarge)
        }
        if (!SteamSocial.enabled(context)) Text(uiStr(R.string.steam_social_disabled))
        else {
            if (status.isNotBlank()) Text(status, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            if (selected == 0L) LazyColumn(Modifier.weight(1f)) {
                if (friends.isEmpty()) item { Text(uiStr(R.string.steam_friends_empty), Modifier.padding(12.dp)) }
                items(friends, key = { it.id }) { friend ->
                    TextButton(onClick = { selected = friend.id }) { ListItem(headlineContent = { Text(friend.name) }, supportingContent = { Text(if (friend.app > 0) uiStr(R.string.steam_friend_ingame) else if (friend.state > 0) uiStr(R.string.steam_ui_connected) else uiStr(R.string.steam_ui_offline)) }) }
                }
            } else {
                val chat = messages[selected].orEmpty()
                LazyColumn(Modifier.weight(1f), reverseLayout = true, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chat.asReversed(), key = { it.key }) { message ->
                        Surface(color = if (message.self) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                            Text((if (message.self) uiStr(R.string.steam_chat_you)+": " else "")+message.text, Modifier.padding(12.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp)) {
                    OutlinedTextField(draft, { draft = it.take(2048) }, Modifier.weight(1f), label = { Text(uiStr(R.string.steam_chat_message)) }, maxLines = 3)
                    IconButton(enabled = draft.isNotBlank(), onClick = { if (SteamSocial.send(context, selected, draft)) draft = "" }) { Icon(Icons.AutoMirrored.Outlined.Send, uiStr(R.string.steam_chat_send)) }
                }
            }
        }
    }
}
