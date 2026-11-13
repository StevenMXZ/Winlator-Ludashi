package com.winlator.cmod.steam

import android.widget.ImageView
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/** Visible contacts only. Hashes cache across guest handoff; failures back off for five minutes. */
@Composable fun SteamFriendAvatar(friend: SteamSocial.Friend) {
    val context = LocalContext.current
    val hash by produceState(friend.avatarHash, friend.id, friend.avatarHash) {
        value = withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "steam-friend-avatars/${friend.id}.txt")
            if (friend.avatarHash.matches(Regex("[a-fA-F0-9]{40}")) && friend.avatarHash.any { it != '0' }) {
                file.parentFile?.mkdirs(); file.writeText(friend.avatarHash); friend.avatarHash
            } else if (file.isFile && (file.length() > 0 || System.currentTimeMillis()-file.lastModified() < 300000)) file.readText()
            else runCatching {
                val connection = URL("https://steamcommunity.com/profiles/${friend.id}/?xml=1").openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }
                val xml = connection.getInputStream().use { input -> val bytes = ByteArray(262144); var used = 0
                    while (used < bytes.size) { val n = input.read(bytes, used, bytes.size-used); if (n < 0) break; used += n }
                    String(bytes, 0, used, Charsets.UTF_8) }
                val parser = android.util.Xml.newPullParser().apply { setInput(xml.reader()) }
                var found = ""
                while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "avatarMedium") {
                        val url = parser.nextText()
                        found = Regex("/([a-fA-F0-9]{40})(?:_medium)?\\.jpg").find(url)?.groupValues?.get(1).orEmpty(); break
                    }
                    parser.next()
                }
                file.parentFile?.mkdirs(); file.writeText(found); found
            }.getOrElse { file.parentFile?.mkdirs(); runCatching { file.writeText("") }; "" }
        }
    }
    if (hash.isBlank()) Icon(Icons.Outlined.Person, null, Modifier.size(40.dp))
    else AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
        update = { Glide.with(it).load("https://avatars.steamstatic.com/${hash}_medium.jpg").override(96, 96).circleCrop().into(it) },
        onRelease = { Glide.with(it.context.applicationContext).clear(it) }, modifier = Modifier.size(40.dp))
}
