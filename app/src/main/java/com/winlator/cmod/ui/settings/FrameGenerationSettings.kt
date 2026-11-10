package com.winlator.cmod.ui.settings

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.cmod.lsfg.LosslessScaling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings shared by the container and shortcut editors. */
@Composable
fun FrameGenerationSettings(
    enabled: Boolean,
    multiplier: Int,
    targetRate: Int,
    flowScale: Int,
    onEnabledChanged: (Boolean) -> Unit,
    onMultiplierChanged: (Int) -> Unit,
    onTargetRateChanged: (Int) -> Unit,
    onFlowScaleChanged: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheStatus by remember { mutableIntStateOf(LosslessScaling.getStatus(context, true)) }
    var importing by remember { mutableStateOf(false) }
    var enableAfterImport by remember { mutableStateOf(false) }

    fun refreshStatus(): Boolean {
        cacheStatus = LosslessScaling.getStatus(context, true)
        return cacheStatus == LosslessScaling.STATUS_OK
    }

    fun showImportResult(status: Int, source: String) {
        importing = false
        cacheStatus = LosslessScaling.getStatus(context, true)
        if (status == LosslessScaling.STATUS_OK) {
            val variant = LosslessScaling.variantName(LosslessScaling.getVariant(context, true))
            Toast.makeText(context, if (source.isBlank()) uiStr(R.string.ui_lsfg_shaders_imported_1_s, variant) else uiStr(R.string.ui_lsfg_shaders_imported_1_s_from_2_s, variant, source), Toast.LENGTH_LONG).show()
            if (enableAfterImport) onEnabledChanged(true)
            enableAfterImport = false
        } else {
            enableAfterImport = false
            Toast.makeText(context, losslessStatusText(status), Toast.LENGTH_LONG).show()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            enableAfterImport = false
            return@rememberLauncherForActivityResult
        }
        importing = true
        scope.launch {
            val status = withContext(Dispatchers.IO) { LosslessScaling.installFrom(context, uri) }
            showImportResult(status, uri.lastPathSegment.orEmpty())
        }
    }

    SettingsCard {
        SettingToggle(uiStr(R.string.ui_enable_lsfg_frame_generation), enabled) { requested ->
            if (!requested) {
                enableAfterImport = false
                onEnabledChanged(false)
            } else if (refreshStatus()) {
                onEnabledChanged(true)
            } else {
                enableAfterImport = true
                picker.launch(arrayOf("application/octet-stream", "application/x-msdownload", "*/*"))
            }
        }

        if (enabled) {
            SettingsDivider()
            val targetLabels = linkedMapOf(
                -1 to "Automatic (display)",
                0 to "Fixed multiplier",
                60 to "Adaptive 60 FPS",
                90 to "Adaptive 90 FPS",
                120 to "Adaptive 120 FPS",
                144 to "Adaptive 144 FPS",
                165 to "Adaptive 165 FPS",
            )
            SettingChoice(uiStr(R.string.ui_generation_mode), targetLabels[targetRate] ?: "Fixed multiplier", targetLabels.values.toList()) { selected ->
                onTargetRateChanged(targetLabels.entries.firstOrNull { it.value == selected }?.key ?: 0)
            }
            if (targetRate == 0) {
                SettingsDivider()
                val multiplierLabel = "${multiplier.coerceIn(2, 4)}x"
                SettingChoice(uiStr(R.string.ui_multiplier), multiplierLabel, listOf("2x", "3x", "4x")) {
                    onMultiplierChanged(it.removeSuffix("x").toIntOrNull()?.coerceIn(2, 4) ?: 2)
                }
            }
            SettingsDivider()
            val qualityLabels = linkedMapOf(40 to "Ultra Performance", 50 to "Performance", 70 to "Balanced", 100 to "Quality")
            val nearestQuality = qualityLabels.keys.minByOrNull { kotlin.math.abs(it - flowScale) } ?: 70
            SettingChoice(uiStr(R.string.ui_quality_preset), qualityLabels[nearestQuality] ?: "Balanced", qualityLabels.values.toList()) { selected ->
                onFlowScaleChanged(qualityLabels.entries.firstOrNull { it.value == selected }?.key ?: 70)
            }
        }

        SettingsDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                if (cacheStatus == LosslessScaling.STATUS_OK) {
                    uiStr(R.string.ui_lossless_dll_ready_1_s, LosslessScaling.variantName(LosslessScaling.getVariant(context, true)))
                } else {
                    losslessStatusText(cacheStatus)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                uiStr(R.string.ui_import_lossless_dll_from_your_own_copy_of_lossless_scaling_a_vers),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = !importing,
                    onClick = { picker.launch(arrayOf("application/octet-stream", "application/x-msdownload", "*/*")) },
                ) { Text(if (importing) uiStr(R.string.ui_importing) else uiStr(R.string.ui_import_lossless_dll)) }
            }
        }
    }
}

private fun losslessStatusText(status: Int): String = when (status) {
    LosslessScaling.STATUS_OK -> uiStr(R.string.ui_lossless_dll_ready)
    LosslessScaling.STATUS_UNREADABLE_FILE -> uiStr(R.string.ui_the_selected_file_could_not_be_read)
    LosslessScaling.STATUS_NOT_PORTABLE_EXECUTABLE -> uiStr(R.string.ui_the_selected_file_is_not_a_valid_lossless_dll)
    LosslessScaling.STATUS_MISSING_SHADERS -> uiStr(R.string.ui_this_lossless_dll_does_not_contain_the_required_spir_v_shaders_im)
    LosslessScaling.STATUS_INVALID_SHADERS -> uiStr(R.string.ui_the_selected_dll_contains_invalid_spir_v_shaders)
    LosslessScaling.STATUS_CACHE_UNUSABLE -> uiStr(R.string.ui_the_lsfg_shader_cache_could_not_be_created)
    else -> uiStr(R.string.ui_lossless_dll_has_not_been_imported)
}
