package com.winlator.cmod.ui.container

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.ui.settings.CpuSelectorRow
import com.winlator.cmod.ui.settings.EnvironmentVariablesEditor
import com.winlator.cmod.ui.settings.SettingChoice
import com.winlator.cmod.ui.settings.SettingToggle
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsDivider
import com.winlator.cmod.winhandler.WinHandler

private val advancedComponents = listOf(
    "direct3d" to "Direct3D",
    "directsound" to "DirectSound",
    "directmusic" to "DirectMusic",
    "directshow" to "DirectShow",
    "directplay" to "DirectPlay",
    "xaudio" to "XAudio",
    "vcrun2010" to "Visual C++ 2010"
)

@Composable
internal fun ContainerAdvancedPane(containerId: Int) {
    val context = LocalContext.current
    val container = remember(containerId) { ContainerManager(context).getContainerById(containerId) } ?: return
    var page by remember { mutableStateOf("Environment") }
    val pages = listOf("Environment", "Components", "Startup & Input", "CPU")
    var environment by remember(container.getEnvVars()) { mutableStateOf(container.getEnvVars()) }
    val components = remember(container.getWinComponents()) {
        mutableStateMapOf<String, Int>().apply { putAll(parseComponents(container.getWinComponents())) }
    }
    var startup by remember { mutableStateOf(container.getStartupSelection().toInt().coerceIn(0, 2)) }
    var exclusive by remember { mutableStateOf(container.isExclusiveXInput()) }
    var xinput by remember { mutableStateOf((container.getInputType() and WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()) != 0) }
    var dinput by remember { mutableStateOf((container.getInputType() and WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()) != 0) }
    var syncCpu by remember { mutableStateOf(container.isSyncCpuTopology()) }
    val cpuCount = remember { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }
    val cpu64 = remember {
        mutableStateListOf<Boolean>().apply { addAll(cpuSelection(container.getCPUList(true), cpuCount)) }
    }
    val cpu32 = remember {
        mutableStateListOf<Boolean>().apply { addAll(cpuSelection(container.getCPUListWoW64(true), cpuCount)) }
    }

    fun saveInput() {
        var inputType = 0
        if (xinput) inputType = inputType or WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()
        if (dinput) inputType = inputType or WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()
        container.setInputType(inputType)
        container.setExclusiveXInput(exclusive)
        container.saveData()
    }

    fun saveCpu() {
        container.setCPUList(cpu64.indices.filter { cpu64[it] }.joinToString(","))
        container.setCPUListWoW64(cpu32.indices.filter { cpu32[it] }.joinToString(","))
        container.setSyncCpuTopology(syncCpu)
        container.saveData()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsCard { SettingChoice(uiStr(R.string.advanced), page, pages) { page = it } }
        when (page) {
            "Environment" -> EnvironmentVariablesEditor(environment, onChanged = {
                environment = it
                container.setEnvVars(it)
                container.saveData()
            })

            "Components" -> SettingsCard {
                advancedComponents.forEachIndexed { index, (key, label) ->
                    val entries = listOf("Builtin (Wine)", "Native (Windows)")
                    val selected = entries[(components[key] ?: 0).coerceIn(0, 1)]
                    SettingChoice(label, selected, entries) { value ->
                        components[key] = entries.indexOf(value).coerceAtLeast(0)
                        container.setWinComponents(
                            advancedComponents.joinToString(",") { (componentKey, _) ->
                                "$componentKey=${components[componentKey] ?: 0}"
                            }
                        )
                        container.saveData()
                    }
                    if (index != advancedComponents.lastIndex) SettingsDivider()
                }
            }

            "Startup & Input" -> SettingsCard {
                val startupEntries = listOf(
                    "Normal (Load all services)",
                    "Essential (Load only essential services)",
                    "Aggressive (Stop services on startup)"
                )
                SettingChoice(uiStr(R.string.startup_selection), startupEntries[startup], startupEntries) { value ->
                    startup = startupEntries.indexOf(value).coerceAtLeast(0)
                    container.setStartupSelection(startup.toByte())
                    container.saveData()
                }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_exclusive_input), exclusive) { enabled ->
                    exclusive = enabled
                    if (!enabled) {
                        xinput = true
                        dinput = true
                    } else if (xinput && dinput) {
                        dinput = false
                    }
                    saveInput()
                }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_enable_xinput), xinput, exclusive) { enabled ->
                    xinput = enabled
                    if (exclusive && enabled && dinput) dinput = false
                    saveInput()
                }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_enable_dinput), dinput, exclusive) { enabled ->
                    dinput = enabled
                    if (exclusive && enabled && xinput) xinput = false
                    saveInput()
                }
            }

            else -> SettingsCard {
                SettingToggle(uiStr(R.string.ui_sync_cpu_topology), syncCpu) {
                    syncCpu = it
                    saveCpu()
                }
                SettingsDivider()
                CpuSelectorRow(uiStr(R.string.processor_affinity), cpu64) { index, checked ->
                    cpu64[index] = checked
                    saveCpu()
                }
                SettingsDivider()
                CpuSelectorRow(uiStr(R.string.processor_affinity_32_bit_apps), cpu32) { index, checked ->
                    cpu32[index] = checked
                    saveCpu()
                }
            }
        }
    }
}

private fun parseComponents(raw: String): Map<String, Int> {
    val result = mutableMapOf<String, Int>()
    raw.split(',').forEach { token ->
        val split = token.indexOf('=')
        if (split > 0) {
            result[token.substring(0, split)] = token.substring(split + 1).toIntOrNull()?.coerceIn(0, 1) ?: 0
        }
    }
    advancedComponents.forEach { (key, _) -> result.putIfAbsent(key, 0) }
    return result
}

private fun cpuSelection(raw: String?, count: Int): List<Boolean> {
    val selected = raw.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).toSet()
    return List(count) { index -> index.toString() in selected }
}
