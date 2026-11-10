package com.winlator.cmod.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.winlator.cmod.R
import com.winlator.cmod.ui.UiStrings

object AppLanguageSettings {
    val supportedTags: List<String> = listOf("", "en", "pt-BR")

    fun select(tags: String) {
        require(tags in supportedTags)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
        UiStrings.invalidate()
    }
}

@Composable
fun AppLanguagePreference() {
    val configuration = LocalConfiguration.current
    var selected by remember(configuration) {
        mutableStateOf(AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }
    val labels = listOf(
        stringResource(R.string.app_language_system),
        stringResource(R.string.app_language_english),
        stringResource(R.string.app_language_pt_br)
    )
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Language, null, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_language), style = MaterialTheme.typography.bodyLarge)
                Text(
                    labels[AppLanguageSettings.supportedTags.indexOf(selected).coerceAtLeast(0)],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Outlined.KeyboardArrowDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppLanguageSettings.supportedTags.forEachIndexed { index, tags ->
                DropdownMenuItem(text = { Text(labels[index]) }, onClick = {
                    expanded = false
                    selected = tags
                    AppLanguageSettings.select(tags)
                })
            }
        }
    }
}
