package com.winlator.cmod.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.core.AudioOutputSettings
import com.winlator.cmod.core.StringUtils
import com.winlator.cmod.ui.uiStr
import kotlin.math.roundToInt

internal val audioOutputKeys = listOf(AudioOutputSettings.API, AudioOutputSettings.BUFFER, AudioOutputSettings.LATENCY)

@Composable
internal fun AudioDriverOptions(
    driver: String,
    driverEntries: List<String>,
    value: (String) -> String,
    inherited: ((String) -> String)? = null,
    onDriverChanged: (String) -> Unit,
    onOptionsConfirmed: (Map<String, String>) -> Unit
) {
    val alsa = driver == "alsa-oboe"
    val hasConfiguration = alsa || driver == "pulse-audio-gn"
    val configurationLabel = uiStr(R.string.audio_configuration)
    var configuring by remember(driver) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            // Keep the saved driver identifier compatible with existing containers.
            val labels = driverEntries.associateWith {
                if (StringUtils.parseIdentifier(it) == "pulse-audio-gn") "PulseAudio-Oboe" else it
            }
            val selected = driverEntries.firstOrNull { StringUtils.parseIdentifier(it).equals(driver, true) }
            SettingChoice(uiStr(R.string.audio_driver), labels[selected] ?: driver, labels.values.toList()) { label ->
                val original = labels.entries.firstOrNull { it.value == label }?.key ?: label
                onDriverChanged(StringUtils.parseIdentifier(original))
            }
        }
        if (hasConfiguration) {
            IconButton(onClick = { configuring = true }, modifier = Modifier.padding(end = 6.dp)) {
                Icon(Icons.Outlined.Settings, contentDescription = configurationLabel)
            }
        }
    }
    if (configuring && hasConfiguration) {
        AudioOutputDialog(
            configurationLabel,
            alsa,
            value = { key -> value(key).ifEmpty { inherited?.invoke(key).orEmpty() } },
            onDismiss = { configuring = false },
            onConfirm = onOptionsConfirmed
        )
    }
}

@Composable
private fun AudioOutputDialog(
    title: String,
    alsa: Boolean,
    value: (String) -> String,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> Unit
) {
    val apiLabels = mapOf(AudioOutputSettings.AAUDIO to "AAudio", AudioOutputSettings.OPENSL_ES to "OpenSL ES")
    var api by remember {
        mutableStateOf(value(AudioOutputSettings.API).takeIf(apiLabels::containsKey) ?: AudioOutputSettings.AAUDIO)
    }
    val amountKey = if (alsa) AudioOutputSettings.BUFFER else AudioOutputSettings.LATENCY
    val increment = if (alsa) AudioOutputSettings.BUFFER_STEP_BYTES else 1
    val maximum = if (alsa) AudioOutputSettings.MAX_BUFFER_BYTES else AudioOutputSettings.MAX_LATENCY_MS
    var amount by remember {
        mutableIntStateOf(
            if (alsa) AudioOutputSettings.bufferSize(value(amountKey))
            else AudioOutputSettings.latencyMs(value(amountKey))
        )
    }
    val amountLabel = uiStr(if (alsa) R.string.audio_buffer_size else R.string.audio_pulse_latency)
    val amountText = uiStr(if (alsa) R.string.audio_buffer_bytes else R.string.audio_latency_ms, amount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                SettingMappedChoice(uiStr(R.string.audio_output_api), api, apiLabels) { api = it }
                SettingsDivider()
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(amountLabel, modifier = Modifier.weight(1f))
                    Text(amountText)
                }
                Slider(
                    value = amount.toFloat(),
                    onValueChange = { amount = (it / increment).roundToInt() * increment },
                    valueRange = 0f..maximum.toFloat(),
                    modifier = Modifier.semantics {
                        contentDescription = amountLabel
                        stateDescription = amountText
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(buildMap {
                    put(AudioOutputSettings.API, api)
                    put(amountKey, amount.toString())
                })
                onDismiss()
            }) { Text(uiStr(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiStr(R.string.cancel)) } }
    )
}
