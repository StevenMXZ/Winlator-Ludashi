package com.winlator.cmod.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R

@Composable
internal fun CodecsSettings(
    decoder: String,
    software: Boolean,
    onDecoder: (String) -> Unit,
    onSoftware: (Boolean) -> Unit
) {
    val entries = stringArrayResource(R.array.builtin_decoder_entries).toList()
    SettingsCard {
        Text(stringResource(R.string.codecs), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
        SettingChoice(stringResource(R.string.builtin_decoder), entries[if (decoder == "ffmpeg") 1 else 0], entries) {
            onDecoder(if (entries.indexOf(it) == 1) "ffmpeg" else "gstreamer")
        }
        SettingsDivider()
        SettingToggle(stringResource(R.string.software_decoding), software, onChanged = onSoftware)
        Text(stringResource(R.string.video_decoder_compatibility_note), modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
