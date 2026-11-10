package com.winlator.cmod.ui.onboarding

import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R

@Composable
internal fun ClassicWelcomeLandscape(
    ready: State<Boolean>,
    progress: State<Int>,
    start: () -> Unit,
    skip: () -> Unit,
    retry: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(.9f), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.winlator_mark_exact), "Winlator", Modifier.size(132.dp))
                Spacer(Modifier.height(14.dp))
                Text(uiStr(R.string.ui_welcome_to_winlator), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(uiStr(R.string.ui_your_lightweight_pc_emulator_for_android), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                ClassicWelcomeCard(Icons.Outlined.Apps, uiStr(R.string.ui_get_started), uiStr(R.string.ui_choose_components), start)
                Spacer(Modifier.height(10.dp))
                ClassicWelcomeCard(Icons.Outlined.SkipNext, uiStr(R.string.ui_skip), uiStr(R.string.ui_use_the_components_bundled_with_winlator_and_configure_everything), skip)
                Spacer(Modifier.height(10.dp))
                ClassicCoreStatusCard(ready, progress, retry)
            }
        }
    }
}
