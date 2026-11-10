package com.winlator.cmod.ui.onboarding

import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R

@Composable
internal fun ClassicWinlatorWelcome(
    ready: State<Boolean>,
    progress: State<Int>,
    start: () -> Unit,
    skip: () -> Unit,
    retry: () -> Unit
) {
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    if (landscape) {
        ClassicWelcomeLandscape(ready, progress, start, skip, retry)
        return
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(.62f))
            Image(
                painterResource(R.drawable.winlator_mark_exact),
                "Winlator",
                Modifier.size(146.dp)
            )
            Spacer(Modifier.height(22.dp))
            Text(
                uiStr(R.string.ui_welcome_to_winlator),
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.height(7.dp))
            Text(
                uiStr(R.string.ui_your_lightweight_pc_emulator_for_android),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(26.dp))
            ClassicWelcomeCard(
                Icons.Outlined.Apps,
                uiStr(R.string.ui_get_started),
                uiStr(R.string.ui_choose_components),
                start
            )
            Spacer(Modifier.height(12.dp))
            ClassicWelcomeCard(
                Icons.Outlined.SkipNext,
                uiStr(R.string.ui_skip),
                uiStr(R.string.ui_use_the_components_bundled_with_winlator_and_configure_everything),
                skip
            )
            Spacer(Modifier.height(12.dp))
            ClassicCoreStatusCard(ready, progress, retry)
            Spacer(Modifier.weight(.38f))
        }
    }
}
