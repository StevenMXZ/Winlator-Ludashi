package com.winlator.cmod.ui.onboarding

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinlatorThemeChoices
import com.winlator.cmod.ui.theme.WinlatorThemeManager

@Composable
internal fun OnboardingThemeScreen(
    onBack: (() -> Unit)? = null,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val current = WinlatorThemeManager.currentTheme()
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp

    if (landscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 42.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(42.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(0.8f)) {
                Text(
                    uiStr(R.string.ui_choose_your_theme),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    uiStr(R.string.ui_pick_the_look_you_prefer_you_can_change_it_later_in_settings),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(
                modifier = Modifier.weight(1.2f).widthIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                WinlatorThemeChoices(
                    selected = current,
                    onSelected = { WinlatorThemeManager.setTheme(context, it) }
                )
                ThemeNavigationButtons(onBack, onContinue)
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.widthIn(max = 620.dp)) {
            Text(
                uiStr(R.string.ui_choose_your_theme),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                uiStr(R.string.ui_pick_the_look_you_prefer_you_can_change_it_later_in_settings),
                modifier = Modifier.padding(top = 7.dp, bottom = 22.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WinlatorThemeChoices(
                selected = current,
                onSelected = { WinlatorThemeManager.setTheme(context, it) }
            )
            ThemeNavigationButtons(onBack, onContinue)
        }
    }
}

@Composable
private fun ThemeNavigationButtons(
    onBack: (() -> Unit)?,
    onContinue: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (onBack != null) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(uiStr(R.string.ui_back))
            }
        }
        Button(onClick = onContinue, modifier = Modifier.weight(1f)) {
            Text(uiStr(R.string._continue))
        }
    }
}
