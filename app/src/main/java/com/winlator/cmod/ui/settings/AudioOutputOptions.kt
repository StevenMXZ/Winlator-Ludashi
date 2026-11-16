package com.winlator.cmod.ui.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.winlator.cmod.R
import com.winlator.cmod.core.AudioOutputSettings
import com.winlator.cmod.ui.uiStr

internal val audioOutputKeys = listOf(AudioOutputSettings.API, AudioOutputSettings.BUFFER, AudioOutputSettings.LATENCY)

@Composable
internal fun AudioOutputOptions(
    driver: String,
    value: (String) -> String,
    inherited: ((String) -> String)? = null,
    onChanged: (String, String?) -> Unit
) {
    if (driver == "alsa-oboe") {
        val apiLabels = linkedMapOf(AudioOutputSettings.AUTO to uiStr(R.string.ui_default),
            "aaudio" to "AAudio", "opensles" to "OpenSL ES")
        AudioOutputChoice(uiStr(R.string.audio_output_api), AudioOutputSettings.API, apiLabels, value, inherited, onChanged)
        AudioNumberChoice(R.string.audio_buffer_size, R.string.audio_buffer_bytes, R.string.audio_buffer_range,
            AudioOutputSettings.BUFFER, listOf(512, 1024, 2048, 4096, 8192, 16384, 32768, 65536),
            AudioOutputSettings::bufferSize, value, inherited, onChanged)
    } else if (driver == "pulse-audio-gn") {
        AudioNumberChoice(R.string.audio_pulse_latency, R.string.audio_latency_ms, R.string.audio_latency_range,
            AudioOutputSettings.LATENCY, listOf(10, 20, 30, 50, 80, 120),
            AudioOutputSettings::latencyMs, value, inherited, onChanged)
    }
}

@Composable
private fun AudioNumberChoice(
    title: Int,
    unit: Int,
    range: Int,
    key: String,
    presets: List<Int>,
    validate: (String) -> Int,
    value: (String) -> String,
    inherited: ((String) -> String)?,
    onChanged: (String, String?) -> Unit
) {
    var custom by remember(key) { mutableStateOf<String?>(null) }
    val current = validate(value(key))
    val parent = validate(inherited?.invoke(key).orEmpty())
    val labels = linkedMapOf(AudioOutputSettings.AUTO to uiStr(R.string.ui_default))
    (presets + current + parent).filter { it > 0 }.distinct().sorted().forEach {
        labels[it.toString()] = uiStr(unit, it)
    }
    labels["custom"] = uiStr(R.string.profile_custom)
    AudioOutputChoice(uiStr(title), key, labels, value, inherited) { _, chosen ->
        if (chosen == "custom") custom = (current.takeIf { it > 0 } ?: parent.takeIf { it > 0 })?.toString().orEmpty()
        else onChanged(key, chosen)
    }
    custom?.let { input ->
        val number = validate(input)
        AlertDialog(
            onDismissRequest = { custom = null },
            title = { Text(uiStr(title)) },
            text = {
                OutlinedTextField(input, { if (it.length <= 5 && it.all { character -> character in '0'..'9' }) custom = it },
                    singleLine = true, label = { Text(uiStr(range)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            },
            confirmButton = {
                TextButton(enabled = number > 0, onClick = {
                    onChanged(key, number.toString())
                    custom = null
                }) { Text(uiStr(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { custom = null }) { Text(uiStr(R.string.cancel)) } }
        )
    }
}

@Composable
private fun AudioOutputChoice(
    label: String,
    key: String,
    labels: Map<String, String>,
    value: (String) -> String,
    inherited: ((String) -> String)?,
    onChanged: (String, String?) -> Unit
) {
    val default = labels.getValue(AudioOutputSettings.AUTO)
    val entries = linkedMapOf<String, String>()
    if (inherited != null) entries[""] = uiStr(R.string.ui_container_default_1_s, labels[inherited(key)] ?: default)
    entries.putAll(labels)
    SettingsDivider()
    val selected = value(key).takeIf(entries::containsKey)
        ?: if (inherited != null) "" else AudioOutputSettings.AUTO
    SettingMappedChoice(label, selected, entries) { choice ->
        onChanged(key, choice.ifEmpty { null })
    }
}
