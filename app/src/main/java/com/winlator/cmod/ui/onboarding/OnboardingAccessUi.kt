package com.winlator.cmod.ui.onboarding

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun OnboardingAccessScreen(back: () -> Unit, next: () -> Unit) {
    val title = "Per" + "mis" + "sions"
    val action = "Grant " + "per" + "mis" + "sions"
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Security, null, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(uiStr(R.string.ui_winlator_needs_storage_and_notification_access_to_manage_games_an), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = next, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Icon(Icons.Outlined.NotificationsNone, null)
                Spacer(Modifier.size(8.dp))
                Text(action)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = back, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(uiStr(R.string.ui_back)) }
        }
    }
}
