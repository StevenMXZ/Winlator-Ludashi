@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlator.cmod.ui.shortcut

import com.winlator.cmod.ui.uiOptionLabel
import com.winlator.cmod.ui.uiStr

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.winlator.cmod.R
import com.winlator.cmod.ShortcutsFragment
import com.winlator.cmod.XrActivity
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.box64.Box64PresetManager
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.container.ShortcutPresetManager
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.GPUInformation
import com.winlator.cmod.core.GameSaveManager
import com.winlator.cmod.core.OpenGLDriverDefaults
import com.winlator.cmod.core.StringUtils
import com.winlator.cmod.fexcore.FEXCorePresetManager
import com.winlator.cmod.inputcontrols.InputControlsManager
import com.winlator.cmod.midi.MidiManager
import com.winlator.cmod.ui.settings.ContainersSettingsActivity
import com.winlator.cmod.ui.settings.CpuSelectorRow
import com.winlator.cmod.ui.settings.DriverOption
import com.winlator.cmod.ui.settings.DxvkAsyncMode
import com.winlator.cmod.ui.settings.EnvironmentVariablesEditor
import com.winlator.cmod.ui.settings.FrameGenerationSettings
import com.winlator.cmod.ui.settings.DDrawWrapperChoice
import com.winlator.cmod.ui.settings.SettingChoice
import com.winlator.cmod.ui.settings.SettingDriverChoice
import com.winlator.cmod.ui.settings.SettingInstallChoice
import com.winlator.cmod.ui.settings.SettingMappedChoice
import com.winlator.cmod.ui.settings.SettingText
import com.winlator.cmod.ui.settings.SettingToggle
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsCatalog
import com.winlator.cmod.ui.settings.SettingsDivider
import com.winlator.cmod.ui.settings.WineRuntimeOption
import com.winlator.cmod.ui.settings.dxvkAsyncMode
import com.winlator.cmod.ui.settings.envPut
import com.winlator.cmod.ui.settings.envValue
import com.winlator.cmod.ui.settings.filterDxvkForVkd3d
import com.winlator.cmod.ui.settings.installAdrenoDriver
import com.winlator.cmod.ui.settings.installRuntimeComponent
import com.winlator.cmod.ui.settings.isDxvkCompatibleWithVkd3d
import com.winlator.cmod.ui.settings.isTurnipDriver
import com.winlator.cmod.ui.settings.isVkd3dEnabled
import com.winlator.cmod.ui.settings.loadSettingsCatalog
import com.winlator.cmod.ui.settings.loadWineRuntimeOptions
import com.winlator.cmod.ui.settings.localeDisplayValue
import com.winlator.cmod.ui.settings.normalizeLocaleValue
import com.winlator.cmod.ui.settings.normalizeResolution
import com.winlator.cmod.ui.settings.readConfig
import com.winlator.cmod.ui.settings.writeConfig
import com.winlator.cmod.winhandler.WinHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val shortcutComponentRowsV2 = listOf(
    "direct3d" to "Direct3D",
    "directsound" to "DirectSound",
    "directmusic" to "DirectMusic",
    "directshow" to "DirectShow",
    "directplay" to "DirectPlay",
    "xaudio" to "XAudio",
    "vcrun2010" to "Visual C++ 2010"
)

private val execArgumentPresetsV2 = listOf(
    "-force-gfx-direct",
    "-force-d3d11-singlethreaded",
    "-force-dx9",
    "-force-d3d9",
    "-force-d3d11",
    "--force-gfx-direct",
    "--force-d3d11-singlethreaded",
    "--force-dx9",
    "--force-d3d9",
    "--force-d3d11",
    "/d3d9"
)

private data class ShortcutCategoryItemV2(val label: String, val icon: ImageVector)

private class ShortcutEditorStateV2(val shortcut: Shortcut) {
    val container: Container = shortcut.container
    val arm64 = container.getWineVersion().contains("arm64ec", true)

    var name by mutableStateOf(com.winlator.cmod.steam.SteamGames.displayName(shortcut))
    var screen by mutableStateOf(normalizeResolution(shortcut.getExtra("screenSize", container.getScreenSize())))
    var displayServer by mutableStateOf(
        if (shortcut.getExtra("displayServer", container.getExtra("displayServer", "native")) == "native")
            "X++" else "X11"
    )
    var renderer by mutableStateOf(if (shortcut.getUseDisplayX()) "DisplayX" else if (shortcut.getRendererNative()) "EGL" else "Vulkan")
    var presentMode by mutableStateOf(shortcut.getRendererPresentMode())
    var rendererDriver by mutableStateOf(shortcut.getRendererDriverId())
    var filterMode by mutableIntStateOf(shortcut.getRendererFilterMode())
    var surfaceFormat by mutableStateOf(shortcut.getSurfaceFormat())
    var trueDisplayX by mutableStateOf(shortcut.getTrueDisplayX())
    var displayXPerformanceMode by mutableStateOf(shortcut.getDisplayXPerformanceMode())
    var displayXPresentAtRefreshRate by mutableStateOf(shortcut.getDisplayXPresentAtRefreshRate())
    var displayXDynamicRange by mutableStateOf(shortcut.getDisplayXDynamicRange())
    var displayXPresentMode by mutableStateOf(shortcut.getDisplayXPresentMode())
    var displayXPrecisePresentation by mutableStateOf(shortcut.getDisplayXPrecisePresentation())
    var frameGenEnabled by mutableStateOf(
        shortcut.getExtra("frameGen", container.getExtra("frameGen", "0")) == "1"
    )
    var frameGenMultiplier by mutableIntStateOf(
        shortcut.getExtra("frameGenMultiplier", container.getExtra("frameGenMultiplier", "2"))
            .toIntOrNull()?.coerceIn(2, 4) ?: 2
    )
    var frameGenTargetRate by mutableIntStateOf(
        shortcut.getExtra("frameGenTargetRate", container.getExtra("frameGenTargetRate", "-1"))
            .toIntOrNull()?.coerceAtLeast(-1) ?: -1
    )
    var frameGenFlowScale by mutableIntStateOf(
        shortcut.getExtra("frameGenFlowScale", container.getExtra("frameGenFlowScale", "70"))
            .toIntOrNull()?.coerceIn(25, 100) ?: 70
    )

    var graphicsDriver by mutableStateOf(StringUtils.parseIdentifier(shortcut.getExtra("graphicsDriver", container.getGraphicsDriver())))
    private val defaultDriverVersion = runCatching {
        val context = container.manager.context
        if (GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, context)) DefaultVersion.WRAPPER_ADRENO else DefaultVersion.WRAPPER
    }.getOrDefault(DefaultVersion.WRAPPER)
    var graphicsConfig by mutableStateOf(
        shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig()).let { raw ->
            if (readConfig(raw, "version", ';').isBlank()) writeConfig(raw, "version", defaultDriverVersion, ';') else raw
        }
    )
    var freedrenoVersion by mutableStateOf(shortcut.getExtra("freedrenoVersion", container.getExtra("freedrenoVersion", "Bundled")))
    var driverVersion by mutableStateOf(readConfig(graphicsConfig, "version", ';').ifBlank { defaultDriverVersion })
    var vulkanVersion by mutableStateOf(readConfig(graphicsConfig, "vulkanVersion", ';').ifBlank { "1.3" })
    var maxMemory by mutableStateOf(readConfig(graphicsConfig, "maxDeviceMemory", ';').ifBlank { "0" })
    var graphicsPresentMode by mutableStateOf(readConfig(graphicsConfig, "presentMode", ';').ifBlank { "mailbox" })
    var syncFrame by mutableStateOf(readConfig(graphicsConfig, "syncFrame", ';') == "1")
    var disablePresentWait by mutableStateOf(readConfig(graphicsConfig, "disablePresentWait", ';') == "1")
    var resourceType by mutableStateOf(readConfig(graphicsConfig, "resourceType", ';').ifBlank { "auto" })
    var bcn by mutableStateOf(readConfig(graphicsConfig, "bcnEmulation", ';').ifBlank { "auto" })
    var bcnType by mutableStateOf(readConfig(graphicsConfig, "bcnEmulationType", ';').ifBlank { "compute" })
    var bcnCache by mutableStateOf(readConfig(graphicsConfig, "bcnEmulationCache", ';') == "1")
    var gpuName by mutableStateOf(readConfig(graphicsConfig, "gpuName", ';').ifBlank { "Device" })
    var blacklistedExtensions by mutableStateOf(readConfig(graphicsConfig, "blacklistedExtensions", ';'))

    var builtinDecoder by mutableStateOf(shortcut.getBuiltinDecoder())
    var softwareDecoding by mutableStateOf(shortcut.getSoftwareDecoding())
    var audio by mutableStateOf(shortcut.getAudioDriver())
    var wrapper by mutableStateOf(StringUtils.parseIdentifier(shortcut.getExtra("dxwrapper", container.getDXWrapper())))
    var wrapperConfig by mutableStateOf(shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig()))
    var dxvkVersion by mutableStateOf(readConfig(wrapperConfig, "version", ',').ifBlank { DefaultVersion.DXVK })
    var vkd3dVersion by mutableStateOf(readConfig(wrapperConfig, "vkd3dVersion", ',').ifBlank { DefaultVersion.VKD3D })
    var vkd3dLevel by mutableStateOf(readConfig(wrapperConfig, "vkd3dLevel", ',').ifBlank { "12_1" })
    var frameRate by mutableStateOf(readConfig(wrapperConfig, "framerate", ',').ifBlank { "0" })
    var maxFrameLatency by mutableStateOf(readConfig(wrapperConfig, "maxFrameLatency", ',') == "1")
    var async by mutableStateOf(readConfig(wrapperConfig, "async", ',') == "1")
    var asyncCache by mutableStateOf(readConfig(wrapperConfig, "asyncCache", ',') == "1")
    var ddrawWrapper by mutableStateOf(readConfig(wrapperConfig, "ddrawrapper", ',').ifBlank { "wined3d" })
    var csmt by mutableStateOf(readConfig(wrapperConfig, "csmt", ',') != "0")
    var strictShaderMath by mutableStateOf(readConfig(wrapperConfig, "strict_shader_math", ',') == "1")
    var offscreenMode by mutableStateOf(readConfig(wrapperConfig, "OffscreenRenderingMode", ',').ifBlank { "fbo" })
    var wineRenderer by mutableStateOf(readConfig(wrapperConfig, "renderer", ',').ifBlank { "vulkan" })
    var videoMemory by mutableStateOf(readConfig(wrapperConfig, "videoMemorySize", ',').ifBlank { "2048" })

    private val emulatorRaw = StringUtils.parseIdentifier(shortcut.getExtra("emulator", container.getEmulator()))
    var emulator by mutableStateOf(if (!arm64) "Box64" else if (emulatorRaw.equals("FEXCore", true)) "FEXCore" else "WOWBox64")
    var fexVersion by mutableStateOf(shortcut.getExtra("fexcoreVersion", container.getFEXCoreVersion().orEmpty()))
    var boxVersion by mutableStateOf(shortcut.getExtra("box64Version", container.getBox64Version().orEmpty()))
    var fexPreset by mutableStateOf(shortcut.getExtra("fexcorePreset", container.getFEXCorePreset()))
    var boxPreset by mutableStateOf(shortcut.getExtra("box64Preset", container.getBox64Preset()))

    var controlsProfile by mutableStateOf(shortcut.getExtra("controlsProfile", "0"))
    var fullscreen by mutableStateOf(shortcut.getExtra("fullscreenStretched", "0") == "1")
    private var inputType by mutableIntStateOf(shortcut.getExtra("inputType", container.getInputType().toString()).toIntOrNull() ?: container.getInputType())
    var exclusive by mutableStateOf(shortcut.getExtra("exclusiveXInput").let { if (it.isBlank()) container.isExclusiveXInput() else it == "1" })
    var forceSsbs by mutableStateOf(shortcut.getExtra("forceSsbs", ""))
    var xinput by mutableStateOf((inputType and WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()) != 0)
    var dinput by mutableStateOf((inputType and WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()) != 0)
    var disableXInput by mutableStateOf(shortcut.getExtra("disableXinput", "0") == "1")
    var relativeMouse by mutableStateOf(shortcut.getExtra("enableRelativeMouse", "0") == "1")
    var disableMouse by mutableStateOf(shortcut.getExtra("disableMouse", "0") == "1")
    var simulatedTouch by mutableStateOf(shortcut.getExtra("simTouchScreen", "0") == "1")
    var syncCpu by mutableStateOf(shortcut.getExtra("syncCpuTopology", if (container.isSyncCpuTopology()) "1" else "0") == "1")
    val cpu = mutableStateListOf<Boolean>().apply {
        val count = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val selected = shortcut.getExtra("cpuList", container.getCPUList(true)).split(',').map(String::trim).toSet()
        addAll(List(count) { it.toString() in selected })
    }

    var startup by mutableIntStateOf(shortcut.getExtra("startupSelection", container.getStartupSelection().toString()).toIntOrNull()?.coerceIn(0, 2) ?: 0)
    var sharpnessEffect by mutableStateOf(shortcut.getExtra("sharpnessEffect", "None"))
    var sharpnessLevel by mutableStateOf(shortcut.getExtra("sharpnessLevel", "100"))
    var sharpnessDenoise by mutableStateOf(shortcut.getExtra("sharpnessDenoise", "100"))
    var lcAll by mutableStateOf(shortcut.getExtra("lc_all", container.getLC_ALL()))
    var midiSoundFont by mutableStateOf(shortcut.getExtra("midiSoundFont", container.getMIDISoundFont()))
    var gameSavesEnabled by mutableStateOf(GameSaveManager.isEnabled(shortcut))
    var autoSaveBackup by mutableStateOf(GameSaveManager.isAutoBackupEnabled(shortcut))
    var execArgs by mutableStateOf(shortcut.getExtra("execArgs"))
    var autoMesaGlVersionOverride by mutableStateOf(
        shortcut.getExtra(
            "autoMesaGlVersionOverride",
            container.getExtra("autoMesaGlVersionOverride", "0")
        ) == "1"
    )
    var envVars by mutableStateOf(shortcut.getExtra("envVars", container.getEnvVars()))
    var winlatorHudMode by mutableIntStateOf(
        shortcut.getExtra("hudMode", container.getExtra("hudMode", if (container.isShowFPS) "2" else "0")).toIntOrNull() ?: 0
    )
    var dxvkHud by mutableStateOf(!envValue(envVars, "DXVK_HUD").isNullOrBlank())
    var renderingMode by mutableStateOf(
        when {
            envValue(envVars, "TU_AUTOTUNE_ALGO").equals("profiled", true) -> "Autotuner Profiled"
            envValue(envVars, "TU_DEBUG")?.split(',')?.any { it.equals("gmem", true) } == true -> "Gmem"
            envValue(envVars, "TU_DEBUG")?.split(',')?.any { it.equals("sysmem", true) } == true -> "Sysmem"
            else -> "None"
        }
    )
    val components = mutableStateMapOf<String, Int>().apply {
        putAll(parseShortcutComponentsV2(shortcut.getExtra("wincomponents", container.getWinComponents())))
    }

    var revision by mutableIntStateOf(0)
    var installing by mutableStateOf<Set<String>>(emptySet())

    private fun save() = shortcut.saveData()

    fun extra(key: String, value: String?) {
        shortcut.putExtra(key, value)
        save()
    }

    fun graphics(key: String, value: String) {
        graphicsConfig = writeConfig(graphicsConfig, key, value, ';')
        shortcut.putExtra("graphicsDriverConfig", graphicsConfig)
        save()
    }

    fun wrapperValue(key: String, value: String) {
        wrapperConfig = writeConfig(wrapperConfig, key, value, ',')
        shortcut.putExtra("dxwrapperConfig", wrapperConfig)
        save()
    }

    fun selectDxvkVersion(version: String) {
        dxvkVersion = version
        wrapperValue("version", version)
        when (dxvkAsyncMode(version)) {
            DxvkAsyncMode.NONE -> {
                async = false; asyncCache = false
                wrapperValue("async", "0"); wrapperValue("asyncCache", "0")
            }
            DxvkAsyncMode.ASYNC -> {
                async = true; asyncCache = false
                wrapperValue("async", "1"); wrapperValue("asyncCache", "0")
            }
            DxvkAsyncMode.GPL_ASYNC -> {
                async = true; asyncCache = true
                wrapperValue("async", "1"); wrapperValue("asyncCache", "1")
            }
        }
    }

    fun setEnvironment(raw: String) {
        if (autoMesaGlVersionOverride && envValue(raw, "MESA_GL_VERSION_OVERRIDE") != "3.3") {
            autoMesaGlVersionOverride = false
            shortcut.putExtra("autoMesaGlVersionOverride", "0")
        }
        envVars = raw
        shortcut.putExtra("envVars", raw.ifBlank { null })
        save()
    }

    fun selectGraphicsDriver(value: String) {
        graphicsDriver = value
        shortcut.putExtra("graphicsDriver", value)
        if (value.equals("freedreno", true)) {
            if (envValue(envVars, "MESA_GL_VERSION_OVERRIDE").isNullOrBlank()) {
                envVars = envPut(envVars, "MESA_GL_VERSION_OVERRIDE", "3.3")
                autoMesaGlVersionOverride = true
            }
        } else if (autoMesaGlVersionOverride) {
            if (envValue(envVars, "MESA_GL_VERSION_OVERRIDE") == "3.3") {
                envVars = envPut(envVars, "MESA_GL_VERSION_OVERRIDE", null)
            }
            autoMesaGlVersionOverride = false
        }
        shortcut.putExtra("autoMesaGlVersionOverride", if (autoMesaGlVersionOverride) "1" else "0")
        shortcut.putExtra("envVars", envVars.ifBlank { null })
        save()
    }

    fun syncOpenGlEnvironment() {
        if (graphicsDriver.equals("freedreno", true) &&
            envValue(envVars, "MESA_GL_VERSION_OVERRIDE").isNullOrBlank()
        ) {
            envVars = envPut(envVars, "MESA_GL_VERSION_OVERRIDE", "3.3")
            autoMesaGlVersionOverride = true
            shortcut.putExtra("autoMesaGlVersionOverride", "1")
            shortcut.putExtra("envVars", envVars)
            save()
        }
    }

    fun updateDxvkHud(enabled: Boolean) {
        dxvkHud = enabled
        setEnvironment(envPut(envVars, "DXVK_HUD", if (enabled) "fps,devinfo,gpuload,version" else null))
    }

    fun applyRenderingMode(mode: String) {
        renderingMode = mode
        var next = envPut(envVars, "TU_DEBUG", null)
        next = envPut(next, "TU_AUTOTUNE_ALGO", null)
        next = when (mode) {
            "Sysmem" -> envPut(next, "TU_DEBUG", "sysmem")
            "Gmem" -> envPut(next, "TU_DEBUG", "gmem")
            "Autotuner Profiled" -> envPut(next, "TU_AUTOTUNE_ALGO", "profiled")
            else -> next
        }
        setEnvironment(next)
    }

    fun saveRenderer() {
        shortcut.setRendererNative(renderer == "EGL")
        shortcut.setUseDisplayX(renderer == "DisplayX")
        shortcut.setRendererPresentMode(presentMode)
        shortcut.setRendererDriverId(rendererDriver)
        shortcut.setRendererFilterMode(filterMode)
        shortcut.setSurfaceFormat(surfaceFormat)
        shortcut.setTrueDisplayX(trueDisplayX)
        shortcut.setDisplayXPerformanceMode(displayXPerformanceMode)
        shortcut.setDisplayXPresentAtRefreshRate(displayXPresentAtRefreshRate)
        shortcut.setDisplayXDynamicRange(displayXDynamicRange)
        shortcut.setDisplayXPresentMode(displayXPresentMode)
        shortcut.setDisplayXPrecisePresentation(displayXPrecisePresentation)
        save()
    }

    fun saveInput() {
        inputType = 0
        if (xinput) inputType = inputType or WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()
        if (dinput) inputType = inputType or WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()
        shortcut.putExtra("inputType", inputType.toString())
        shortcut.putExtra("exclusiveXInput", if (exclusive) "1" else "0")
        save()
    }

    fun saveCpu() {
        shortcut.putExtra("cpuList", cpu.indices.filter { cpu[it] }.joinToString(","))
        shortcut.putExtra("syncCpuTopology", if (syncCpu) "1" else "0")
        save()
    }

    fun saveComponents() {
        shortcut.putExtra("wincomponents", shortcutComponentRowsV2.joinToString(",") { (key, _) -> "$key=${components[key] ?: 0}" })
        save()
    }
}

@Composable
internal fun ShortcutEditorV2(fragment: Fragment, shortcut: Shortcut, close: () -> Unit) {
    val context = fragment.requireContext()
    val state = remember(shortcut.file.path) {
        OpenGLDriverDefaults.initialize(context, shortcut.container)
        ShortcutEditorStateV2(shortcut)
    }
    val scope = rememberCoroutineScope()
    var presetsMenu by remember { mutableStateOf(false) }
    var importDialog by remember { mutableStateOf<Map<String, String>?>(null) }
    var copyDialog by remember { mutableStateOf(false) }
    var presetGroups by remember { mutableStateOf(ShortcutPresetManager.allGroups().toList()) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) runCatching { ShortcutPresetManager.writeSettings(context, uri, shortcut) }
            .onSuccess { Toast.makeText(context, uiStr(R.string.ui_preset_settings_exported), Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, uiStr(R.string.ui_preset_export_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching { ShortcutPresetManager.read(context, uri) }
            .onSuccess { importDialog = it; presetGroups = ShortcutPresetManager.allGroups().toList() }
            .onFailure { Toast.makeText(context, uiStr(R.string.ui_preset_import_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(shortcut.file.path) {
        state.syncOpenGlEnvironment()
    }
    val manager = remember { ContainerManager(context) }
    val containers = remember { manager.containers.toList() }

    val runtimeOptions by produceState<List<WineRuntimeOption>>(emptyList()) {
        value = loadWineRuntimeOptions(context)
    }
    fun environmentLabel(container: Container): String {
        val runtime = runtimeOptions.firstOrNull { it.id == container.wineVersion }?.label
            ?: container.wineVersion
        return "${container.name} · $runtime"
    }

    val screenEntries = remember { context.resources.getStringArray(R.array.screen_size_entries).toList() }
    val graphicsEntries = remember { context.resources.getStringArray(R.array.graphics_driver_entries).toList() }
    val audioEntries = remember { context.resources.getStringArray(R.array.audio_driver_entries).toList() }
    val wrapperEntries = remember { context.resources.getStringArray(R.array.dxwrapper_entries).toList() }
    val localeEntries = remember { listOf("Default") + context.resources.getStringArray(R.array.some_lc_all).toList() }
    val soundFonts = remember { loadShortcutSoundFontsV2(context) }
    val gpuNames = remember { loadShortcutGpuNamesV2(context) }
    val fexPresets = remember { FEXCorePresetManager.getPresets(context).associate { it.id to it.name } }
    val boxPresets = remember { Box64PresetManager.getPresets("box64", context).associate { it.id to it.name } }
    val profiles = remember {
        linkedMapOf("0" to "None").apply {
            InputControlsManager(context).getProfiles(true).forEach { put(it.id.toString(), it.name) }
        }
    }
    val catalog by produceState<SettingsCatalog?>(null, state.arm64, state.revision, state.dxvkVersion, state.vkd3dVersion, state.driverVersion) {
        value = loadSettingsCatalog(
            context, state.arm64, state.dxvkVersion, state.vkd3dVersion,
            state.fexVersion, state.boxVersion, state.driverVersion
        )
    }

    fun installRuntime(type: String, version: String, done: (String) -> Unit) {
        val key = "$type:$version"
        if (key in state.installing) return
        state.installing = state.installing + key
        scope.launch {
            val installed = installRuntimeComponent(context, type, version)
            state.installing = state.installing - key
            if (installed == null) Toast.makeText(context, uiStr(R.string.ui_unable_to_install_1_s, version), Toast.LENGTH_SHORT).show()
            else {
                done(installed)
                state.revision++
            }
        }
    }

    fun installDriver(option: DriverOption) {
        val key = "driver:${option.remoteUrl ?: option.id}"
        if (key in state.installing) return
        state.installing = state.installing + key
        scope.launch {
            val installed = installAdrenoDriver(context, option)
            state.installing = state.installing - key
            if (installed == null) Toast.makeText(context, uiStr(R.string.ui_unable_to_install_1_s, option.label), Toast.LENGTH_SHORT).show()
            else {
                state.driverVersion = installed
                state.graphics("version", installed)
                state.revision++
            }
        }
    }

    fun closeEditor() {
        renameShortcutV2(shortcut, state.name)
        if (fragment is ShortcutsFragment) fragment.loadShortcutsList()
        close()
    }

    fun enterContainer() {
        val activity = fragment.requireActivity()
        if (!XrActivity.isEnabled(context)) {
            activity.startActivity(Intent(activity, XServerDisplayActivity::class.java).putExtra("container_id", state.container.id))
        } else {
            XrActivity.openIntent(activity, state.container.id, null)
        }
    }

    fun createContainer() {
        context.startActivity(Intent(context, ContainersSettingsActivity::class.java))
    }

    fun changeContainer(targetId: Int) {
        val target = containers.firstOrNull { it.id == targetId } ?: return
        if (target.id == state.container.id) return
        if (shortcut.cloneToContainer(target)) {
            shortcut.getExtra("steamAppId").toIntOrNull()?.let { steamId ->
                Shortcut(target, java.io.File(target.desktopDir, shortcut.file.name)).apply { putExtra("steamConfigured", "0"); saveData() }
                context.getSharedPreferences("steam-settings", 0).edit().putInt("steam-container-$steamId", target.id).apply()
                com.winlator.cmod.steam.SteamGames.changed()
            }
            Toast.makeText(context, uiStr(R.string.ui_shortcut_copied_to_1_s, target.name), Toast.LENGTH_SHORT).show()
            if (fragment is ShortcutsFragment) fragment.loadShortcutsList()
            close()
            if (shortcut.getExtra("steamAppId").toIntOrNull() != null) {
                ShortcutSettingsComposeDialog.show(fragment, Shortcut(target, java.io.File(target.desktopDir, shortcut.file.name)))
            }
        }
    }

    var category by remember { mutableStateOf("General") }
    val categories = listOf(
        ShortcutCategoryItemV2("General", Icons.Outlined.Settings),
        ShortcutCategoryItemV2("Video", Icons.Outlined.Monitor),
        ShortcutCategoryItemV2("Compatibility", Icons.Outlined.Tune),
        ShortcutCategoryItemV2("Input", Icons.Outlined.Gamepad),
        ShortcutCategoryItemV2("Environment", Icons.Outlined.Terminal),
        ShortcutCategoryItemV2("Advanced", Icons.Outlined.Terminal)
    )
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(state.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = ::closeEditor) { Icon(Icons.Outlined.ArrowBack, null) } },
                actions = {
                    Box {
                        IconButton(onClick = { presetsMenu = true }) { Icon(Icons.Outlined.MoreVert, uiStr(R.string.ui_preset_presets)) }
                        DropdownMenu(expanded = presetsMenu, onDismissRequest = { presetsMenu = false }) {
                            DropdownMenuItem(text = { Text(uiStr(R.string.ui_preset_copy_from_game)) }, onClick = { presetsMenu = false; copyDialog = true })
                            DropdownMenuItem(text = { Text(uiStr(R.string.ui_preset_import_settings)) }, onClick = { presetsMenu = false; importLauncher.launch("*/*") })
                            DropdownMenuItem(text = { Text(uiStr(R.string.ui_preset_export_settings)) }, onClick = { presetsMenu = false; exportLauncher.launch(shortcut.name + "-settings.desktop") })
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (landscape) {
            Row(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    Modifier.width(220.dp).fillMaxHeight(),
                    shape = RoundedCornerShape(15.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item {
                            Text(
                                uiStr(R.string.ui_shortcut_settings),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        items(categories) { item ->
                            ShortcutNavItemV2(item.label, item.icon, category == item.label) { category = item.label }
                        }
                    }
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(category) {
                        ShortcutCategoryV2(
                            category, state, catalog, screenEntries, graphicsEntries, audioEntries,
                            wrapperEntries, localeEntries, soundFonts, gpuNames, fexPresets, boxPresets,
                            profiles, containers, ::environmentLabel, ::changeContainer, ::createContainer, ::enterContainer,
                            ::installRuntime, ::installDriver, context
                        )
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(categories) { item ->
                        ShortcutNavItemV2(item.label, item.icon, category == item.label) { category = item.label }
                    }
                }
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(category) {
                        ShortcutCategoryV2(
                            category, state, catalog, screenEntries, graphicsEntries, audioEntries,
                            wrapperEntries, localeEntries, soundFonts, gpuNames, fexPresets, boxPresets,
                            profiles, containers, ::environmentLabel, ::changeContainer, ::createContainer, ::enterContainer,
                            ::installRuntime, ::installDriver, context
                        )
                    }
                }
            }
        }
    }
    if (copyDialog) {
        val otherGames = remember { manager.loadShortcuts().filter { it.file.absolutePath != shortcut.file.absolutePath } }
        AlertDialog(
            onDismissRequest = { copyDialog = false },
            title = { Text(uiStr(R.string.ui_preset_copy_settings_from_game)) },
            text = {
                LazyColumn {
                    items(otherGames) { source ->
                        TextButton(onClick = {
                            importDialog = ShortcutPresetManager.effectiveSettings(source)
                            presetGroups = ShortcutPresetManager.allGroups().toList()
                            copyDialog = false
                        }) { Text(source.name) }
                    }
                    if (otherGames.isEmpty()) item { Text(uiStr(R.string.ui_preset_no_other_games)) }
                }
            },
            confirmButton = { TextButton(onClick = { copyDialog = false }) { Text(uiStr(R.string.ui_preset_cancel)) } }
        )
    }
    importDialog?.let { values ->
        AlertDialog(
            onDismissRequest = { importDialog = null },
            title = { Text(uiStr(R.string.ui_preset_apply_game_settings)) },
            text = {
                Column {
                    Text(uiStr(R.string.ui_preset_choose_groups))
                    val groupLabels = listOf(
                        uiStr(R.string.ui_preset_group_graphics_renderer),
                        uiStr(R.string.ui_preset_group_dxvk_vkd3d),
                        uiStr(R.string.ui_preset_group_box64_fexcore),
                        uiStr(R.string.ui_preset_group_environment),
                        uiStr(R.string.ui_preset_group_display),
                        uiStr(R.string.ui_preset_group_controls)
                    )
                    groupLabels.forEachIndexed { index, label ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = presetGroups[index], onCheckedChange = { enabled ->
                                presetGroups = presetGroups.toMutableList().also { it[index] = enabled }
                            })
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { ShortcutPresetManager.apply(shortcut, values, presetGroups.toBooleanArray()) }
                        .onSuccess {
                            Toast.makeText(context, uiStr(R.string.ui_preset_settings_applied), Toast.LENGTH_SHORT).show()
                            importDialog = null
                            closeEditor()
                        }
                        .onFailure { Toast.makeText(context, uiStr(R.string.ui_preset_apply_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
                }) { Text(uiStr(R.string.ui_apply)) }
            },
            dismissButton = { TextButton(onClick = { importDialog = null }) { Text(uiStr(R.string.ui_preset_cancel)) } }
        )
    }
}

@Composable
private fun ShortcutGameSavesCard(s: ShortcutEditorStateV2, context: Context) {
    val globalEnabled = GameSaveManager.isGlobalAutoBackupEnabled(context)
    val active = globalEnabled || s.gameSavesEnabled
    val scope = rememberCoroutineScope()
    var roots by remember(s.shortcut.file.path) { mutableStateOf<List<String>>(emptyList()) }
    var latestBackup by remember(s.shortcut.file.path) {
        mutableStateOf(GameSaveManager.getLatestBackup(s.shortcut))
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(active, refreshKey) {
        if (active) {
            roots = withContext(Dispatchers.IO) { GameSaveManager.getSaveRoots(s.shortcut) }
            latestBackup = GameSaveManager.getLatestBackup(s.shortcut)
        } else {
            roots = emptyList()
            latestBackup = GameSaveManager.getLatestBackup(s.shortcut)
        }
    }

    SettingsCard {
        SettingToggle(
            uiStr(R.string.ui_game_saves),
            active,
            enabled = !globalEnabled
        ) { enabled ->
            s.gameSavesEnabled = enabled
            if (!enabled) s.autoSaveBackup = false
            GameSaveManager.setEnabled(s.shortcut, enabled)
        }

        if (globalEnabled) {
            SettingsDivider()
            Text(
                uiStr(R.string.ui_enabled_globally_in_winlator_settings_all_shortcuts_are_backed_up),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (active) {
            SettingsDivider()
            SettingToggle(
                uiStr(R.string.ui_automatic_backup),
                globalEnabled || s.autoSaveBackup,
                enabled = !globalEnabled
            ) { enabled ->
                s.autoSaveBackup = enabled
                GameSaveManager.setAutoBackupEnabled(s.shortcut, enabled)
            }

            SettingsDivider()
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(uiStr(R.string.ui_backup_folder), style = MaterialTheme.typography.labelLarge)
                Text(
                    uiStr(R.string.ui_winlator_saves_1_s, GameSaveManager.getGameDir(s.shortcut).name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(uiStr(R.string.ui_latest_backup), style = MaterialTheme.typography.labelLarge)
                Text(
                    latestBackup?.let(::shortcutBackupLabel) ?: uiStr(R.string.ui_no_backup_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(uiStr(R.string.ui_save_locations), style = MaterialTheme.typography.labelLarge)
                if (roots.isEmpty()) {
                    Text(
                        uiStr(R.string.ui_no_specific_save_folder_detected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    roots.forEach { root ->
                        Text("• $root", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            SettingsDivider()
            OutlinedButton(
                onClick = {
                    if (!busy) {
                        busy = true
                        message = uiStr(R.string.ui_scanning_save_locations)
                        scope.launch {
                            roots = withContext(Dispatchers.IO) {
                                GameSaveManager.rediscoverSaveRoots(s.shortcut)
                            }
                            message = if (roots.isEmpty()) {
                                uiStr(R.string.ui_no_per_game_save_folder_detected)
                            } else {
                                uiStr(R.string.ui_detected_1_s_save_location_2_s, roots.size)
                            }
                            refreshKey++
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                enabled = !busy
            ) {
                Text(uiStr(R.string.ui_rescan_save_locations))
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (!busy) {
                            busy = true
                            message = uiStr(R.string.ui_backing_up_saves)
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    GameSaveManager.backup(s.shortcut, false)
                                }
                                message = when {
                                    result.ok && result.wholeProfile ->
                                        uiStr(R.string.ui_backup_complete_1_s_files_the_wine_profile_was_used_because_no_pe, result.fileCount)
                                    result.ok -> uiStr(R.string.ui_backup_complete_1_s_files, result.fileCount)
                                    else -> uiStr(R.string.ui_backup_failed_1_s, result.error ?: "unknown error")
                                }
                                refreshKey++
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy
                ) {
                    Text(uiStr(R.string.ui_back_up_now))
                }

                OutlinedButton(
                    onClick = {
                        if (!busy) {
                            busy = true
                            message = uiStr(R.string.ui_restoring_latest_backup)
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    GameSaveManager.restoreLatest(s.shortcut)
                                }
                                message = if (result.ok) {
                                    "Restored ${result.fileCount} files."
                                } else {
                                    uiStr(R.string.ui_restore_failed_1_s, result.error ?: "unknown error")
                                }
                                refreshKey++
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy && latestBackup != null
                ) {
                    Text(uiStr(R.string.ui_restore))
                }
            }

            message?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun shortcutBackupLabel(file: File): String {
    val date = SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    return "${file.name}\n$date"
}

@Composable
private fun ShortcutNavItemV2(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(16.dp))
            Text(
                uiOptionLabel(label),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ShortcutCategoryV2(
    category: String,
    s: ShortcutEditorStateV2,
    catalog: SettingsCatalog?,
    screenEntries: List<String>,
    graphicsEntries: List<String>,
    audioEntries: List<String>,
    wrapperEntries: List<String>,
    localeEntries: List<String>,
    soundFonts: List<String>,
    gpuNames: List<String>,
    fexPresets: Map<String, String>,
    boxPresets: Map<String, String>,
    profiles: Map<String, String>,
    containers: List<Container>,
    environmentLabel: (Container) -> String,
    changeContainer: (Int) -> Unit,
    createContainer: () -> Unit,
    enterContainer: () -> Unit,
    installRuntime: (String, String, (String) -> Unit) -> Unit,
    installDriver: (DriverOption) -> Unit,
    context: Context
) {
    when (category) {
        "General" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard { SettingText(uiStr(R.string.name), s.name) { s.name = it } }
            SettingsCard {
                val currentLabel = environmentLabel(s.container)
                if (containers.size > 1) {
                    val labels = containers.associate { it.id to environmentLabel(it) }
                    SettingChoice(uiStr(R.string.ui_environment), currentLabel, labels.values.toList()) { selected ->
                        labels.entries.firstOrNull { it.value == selected }?.key?.let(changeContainer)
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(uiStr(R.string.ui_environment), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(currentLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        }
                        IconButton(onClick = createContainer) { Icon(Icons.Outlined.Add, uiStr(R.string.ui_create_container)) }
                    }
                }
                SettingsDivider()
                Button(onClick = enterContainer, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.size(7.dp))
                    Text(uiStr(R.string.ui_enter_container))
                }
            }
            SettingsCard {
                SettingChoice(uiStr(R.string.audio_driver), audioEntries.firstOrNull { StringUtils.parseIdentifier(it).equals(s.audio, true) } ?: s.audio, audioEntries) {
                    s.audio = StringUtils.parseIdentifier(it); s.extra("audioDriver", s.audio)
                }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_fullscreen_stretched), s.fullscreen) {
                    s.fullscreen = it; s.extra("fullscreenStretched", if (it) "1" else null)
                }
            }
            SettingsCard {
                val hudEntries = listOf("Off", "Classic", "Modern")
                SettingChoice(uiStr(R.string.ui_winlator_hud), hudEntries.getOrElse(s.winlatorHudMode) { "Off" }, hudEntries) {
                    s.winlatorHudMode = hudEntries.indexOf(it).coerceAtLeast(0)
                    s.extra("hudMode", s.winlatorHudMode.toString())
                }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_dxvk_hud), s.dxvkHud) { s.updateDxvkHud(it) }
            }
            SettingsCard {
                val locale = localeDisplayValue(s.lcAll)
                SettingChoice(uiStr(R.string.ui_locale_lc_all), locale, localeEntries) {
                    s.lcAll = normalizeLocaleValue(it)
                    s.extra("lc_all", s.lcAll.ifBlank { null })
                }
                SettingsDivider()
                val sound = s.midiSoundFont.ifBlank { "Disabled" }
                SettingChoice(uiStr(R.string.midi_sound_font), sound, soundFonts) {
                    s.midiSoundFont = if (it == "Disabled") "" else it; s.extra("midiSoundFont", s.midiSoundFont.ifBlank { null })
                }
            }
            ShortcutGameSavesCard(s, context)
        }

        "Video" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                var customScreenSelected by remember {
                    mutableStateOf(screenEntries.none { normalizeResolution(it).equals(s.screen, true) })
                }
                val screenChoice = if (customScreenSelected) "Custom"
                else screenEntries.firstOrNull { normalizeResolution(it).equals(s.screen, true) } ?: "Custom"
                SettingChoice(uiStr(R.string.screen_size), screenChoice, screenEntries) {
                    if (it.equals("Custom", true)) {
                        customScreenSelected = true
                    } else {
                        customScreenSelected = false
                        s.screen = normalizeResolution(it)
                        s.extra("screenSize", s.screen)
                    }
                }
                if (screenChoice == "Custom") {
                    SettingsDivider()
                    SettingText(uiStr(R.string.ui_custom_resolution), s.screen) { value ->
                        s.screen = value
                        if (Regex("\\d{2,5}x\\d{2,5}").matches(value.trim())) s.extra("screenSize", normalizeResolution(value))
                    }
                }
                SettingsDivider()
                SettingChoice(uiStr(R.string.ui_display_server), s.displayServer,
                    listOf("X11", "X++")) {
                    s.displayServer = it
                    s.extra("displayServer", if (it == "X++") "native" else "java")
                }
                SettingsDivider()
                SettingChoice(uiStr(R.string.ui_renderer), s.renderer, listOf("Vulkan", "EGL", "DisplayX")) {
                    s.renderer = it
                    s.surfaceFormat = if (it == "DisplayX") "rgba8" else "bgra8"
                    if (it == "EGL" && s.filterMode > 1) s.filterMode = 0
                    s.saveRenderer()
                }
                SettingsDivider()
                SettingChoice(
                    uiStr(R.string.ui_surface_format),
                    if (s.surfaceFormat == "bgra8") "BGRA" else "RGBA",
                    listOf("RGBA", "BGRA")
                ) {
                    s.surfaceFormat = if (it == "BGRA") "bgra8" else "rgba8"
                    s.saveRenderer()
                }
                if (s.renderer == "DisplayX") {
                    SettingsDivider()
                    SettingChoice(uiStr(R.string.ui_displayx_backend), if (s.trueDisplayX) "DisplayX+" else "X11", listOf("X11", "DisplayX+")) {
                        s.trueDisplayX = it == "DisplayX+"
                        s.saveRenderer()
                    }
                    SettingsDivider()
                    SettingToggle(uiStr(R.string.ui_performance_mode), s.displayXPerformanceMode) {
                        s.displayXPerformanceMode = it
                        s.saveRenderer()
                    }
                    SettingsDivider()
                    SettingToggle(uiStr(R.string.ui_present_at_refresh_rate), s.displayXPresentAtRefreshRate) {
                        s.displayXPresentAtRefreshRate = it
                        s.saveRenderer()
                    }
                    SettingsDivider()
                    SettingChoice(uiStr(R.string.ui_present_mode), s.displayXPresentMode, listOf("auto", "mailbox", "fifo")) {
                        s.displayXPresentMode = it
                        s.saveRenderer()
                    }
                    if (s.trueDisplayX) {
                        SettingsDivider()
                        SettingChoice(uiStr(R.string.ui_dynamic_range), s.displayXDynamicRange.uppercase(), listOf("SDR", "HDR")) {
                            s.displayXDynamicRange = it.lowercase()
                            s.saveRenderer()
                        }
                    }
                } else {
                    if (s.renderer != "EGL") {
                        SettingsDivider()
                        SettingChoice(uiStr(R.string.ui_present_mode), s.presentMode, listOf("mailbox", "fifo")) {
                            s.presentMode = it
                            s.saveRenderer()
                        }
                        catalog?.let { c ->
                            SettingsDivider()
                            SettingMappedChoice(uiStr(R.string.ui_renderer_driver), s.rendererDriver, c.rendererDrivers) {
                                s.rendererDriver = it
                                s.saveRenderer()
                            }
                        }
                    }
                    SettingsDivider()
                    val filters = if (s.renderer == "EGL") listOf("Bilinear", "Nearest neighbor")
                    else listOf("Bilinear", "Nearest neighbor", "Snapdragon Super Resolution", "AMD FidelityFX Super Resolution", "Lanczos 2 (16-tap)", "Color Boost")
                    SettingChoice(uiStr(R.string.ui_texture_filter), filters.getOrElse(s.filterMode) { filters.first() }, filters) {
                        s.filterMode = filters.indexOf(it).coerceAtLeast(0)
                        s.saveRenderer()
                    }
                }
            }
            if (s.renderer == "Vulkan") {
                FrameGenerationSettings(
                    enabled = s.frameGenEnabled,
                    multiplier = s.frameGenMultiplier,
                    targetRate = s.frameGenTargetRate,
                    flowScale = s.frameGenFlowScale,
                    onEnabledChanged = {
                        s.frameGenEnabled = it
                        s.extra("frameGen", if (it) "1" else "0")
                    },
                    onMultiplierChanged = {
                        s.frameGenMultiplier = it
                        s.extra("frameGenMultiplier", it.toString())
                    },
                    onTargetRateChanged = {
                        s.frameGenTargetRate = it
                        s.extra("frameGenTargetRate", it.toString())
                    },
                    onFlowScaleChanged = {
                        s.frameGenFlowScale = it
                        s.extra("frameGenFlowScale", it.toString())
                    },
                )
            }
            SettingsCard {
                SettingChoice(uiStr(R.string.graphics_driver), graphicsEntries.firstOrNull { StringUtils.parseIdentifier(it).equals(s.graphicsDriver, true) } ?: s.graphicsDriver, graphicsEntries) {
                    s.selectGraphicsDriver(StringUtils.parseIdentifier(it))
                }
                if (s.graphicsDriver.equals("freedreno", true)) {
                    SettingsDivider()
                    SettingMappedChoice("Freedreno OpenGL", s.freedrenoVersion,
                        com.winlator.cmod.contents.OpenGLDriverManager(context).versionLabels(s.freedrenoVersion)) {
                        s.freedrenoVersion = it
                        s.extra("freedrenoVersion", it)
                    }
                }
                catalog?.let { c ->
                    SettingsDivider()
                    SettingDriverChoice(uiStr(R.string.ui_driver_version), s.driverVersion, c.drivers, s.installing, installDriver) {
                        s.driverVersion = it; s.graphics("version", it)
                    }
                }
                SettingsDivider()
                ShortcutVulkanExtensionsV2(context, s.driverVersion, s.blacklistedExtensions) {
                    s.blacklistedExtensions = it; s.graphics("blacklistedExtensions", it)
                }
                if (isTurnipDriver(s.driverVersion)) {
                    SettingsDivider()
                    SettingChoice(uiStr(R.string.ui_rendering_mode), s.renderingMode, listOf("None", "Sysmem", "Gmem", "Autotuner Profiled")) { s.applyRenderingMode(it) }
                }
                SettingsDivider()
                SettingChoice(uiStr(R.string.graphics_driver_vulkan_version), s.vulkanVersion, listOf("1.1", "1.2", "1.3")) { s.vulkanVersion = it; s.graphics("vulkanVersion", it) }
                SettingsDivider()
                SettingChoice(uiStr(R.string.gpu_name), s.gpuName, gpuNames) { s.gpuName = it; s.graphics("gpuName", it) }
                SettingsDivider()
                SettingChoice(uiStr(R.string.max_device_memory), s.maxMemory, listOf("0", "512", "1024", "2048", "4096", "8192", "12288", "16384")) {
                    s.maxMemory = it; s.graphics("maxDeviceMemory", it)
                }
                SettingsDivider()
                SettingChoice(uiStr(R.string.ui_driver_present_mode), s.graphicsPresentMode, listOf("mailbox", "fifo", "immediate", "relaxed")) {
                    s.graphicsPresentMode = it; s.graphics("presentMode", it)
                }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_sync_frame), s.syncFrame) { s.syncFrame = it; s.graphics("syncFrame", if (it) "1" else "0") }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_disable_present_wait), s.disablePresentWait) { s.disablePresentWait = it; s.graphics("disablePresentWait", if (it) "1" else "0") }
                SettingsDivider()
                SettingChoice(uiStr(R.string.ui_resource_type), s.resourceType, listOf("auto", "dmabuf", "ahb", "opaque")) { s.resourceType = it; s.graphics("resourceType", it) }
                SettingsDivider()
                SettingChoice(uiStr(R.string.ui_bcn_emulation), s.bcn, listOf("none", "partial", "full", "auto")) { s.bcn = it; s.graphics("bcnEmulation", it) }
                SettingsDivider()
                SettingChoice(uiStr(R.string.ui_bcn_emulation_type), s.bcnType, listOf("software", "compute")) { s.bcnType = it; s.graphics("bcnEmulationType", it) }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_bcn_emulation_cache), s.bcnCache) { s.bcnCache = it; s.graphics("bcnEmulationCache", if (it) "1" else "0") }
            }
        }

        "Compatibility" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                val shown = wrapperEntries.firstOrNull { StringUtils.parseIdentifier(it).equals(s.wrapper, true) } ?: s.wrapper
                SettingChoice(uiStr(R.string.dxwrapper), shown, wrapperEntries) { s.wrapper = StringUtils.parseIdentifier(it); s.extra("dxwrapper", s.wrapper) }
                if (s.wrapper.contains("dxvk", true)) {
                    catalog?.let { c ->
                        val dxvkCatalog = filterDxvkForVkd3d(c.dxvk, s.vkd3dVersion)
                        SettingsDivider()
                        SettingInstallChoice(uiStr(R.string.dxvk_version), s.dxvkVersion, dxvkCatalog, s.installing, "DXVK",
                            { v -> installRuntime("DXVK", v) { installed -> s.selectDxvkVersion(installed) } }) { s.selectDxvkVersion(it) }
                        SettingsDivider()
                        SettingInstallChoice(uiStr(R.string.vkd3d_version), s.vkd3dVersion, c.vkd3d, s.installing, "VKD3D",
                            { v -> installRuntime("VKD3D", v) { installed ->
                                s.vkd3dVersion = installed
                                s.wrapperValue("vkd3dVersion", installed)
                                if (isVkd3dEnabled(installed) && !isDxvkCompatibleWithVkd3d(s.dxvkVersion)) {
                                    c.dxvk.all.firstOrNull { candidate -> isDxvkCompatibleWithVkd3d(candidate) && candidate in c.dxvk.installed }?.let(s::selectDxvkVersion)
                                }
                            } }) {
                            s.vkd3dVersion = it
                            s.wrapperValue("vkd3dVersion", it)
                            if (isVkd3dEnabled(it) && !isDxvkCompatibleWithVkd3d(s.dxvkVersion)) {
                                c.dxvk.all.firstOrNull { candidate -> isDxvkCompatibleWithVkd3d(candidate) && candidate in c.dxvk.installed }?.let(s::selectDxvkVersion)
                            }
                        }
                    }
                    SettingsDivider()
                    SettingChoice(uiStr(R.string.vkd3d_feature_level), s.vkd3dLevel, listOf("12_0", "12_1", "12_2", "11_1", "11_0", "10_1", "10_0", "9_3", "9_2", "9_1")) { s.vkd3dLevel = it; s.wrapperValue("vkd3dLevel", it) }
                    SettingsDivider()
                    SettingText(uiStr(R.string.frame_rate), s.frameRate) { s.frameRate = it.filter(Char::isDigit).take(4); s.wrapperValue("framerate", s.frameRate.ifBlank { "0" }) }
                    SettingsDivider()
                    SettingToggle(uiStr(R.string.ui_max_frame_latency), s.maxFrameLatency) { s.maxFrameLatency = it; s.wrapperValue("maxFrameLatency", if (it) "1" else "0") }
                    val asyncMode = dxvkAsyncMode(s.dxvkVersion)
                    if (asyncMode != DxvkAsyncMode.NONE) {
                        SettingsDivider()
                        SettingToggle(uiStr(R.string.ui_async), s.async) { s.async = it; s.wrapperValue("async", if (it) "1" else "0") }
                    }
                    if (asyncMode == DxvkAsyncMode.GPL_ASYNC) {
                        SettingsDivider()
                        SettingToggle(uiStr(R.string.ui_async_cache), s.asyncCache) { s.asyncCache = it; s.wrapperValue("asyncCache", if (it) "1" else "0") }
                    }
                    SettingsDivider()
                    DDrawWrapperChoice(s.ddrawWrapper) { s.ddrawWrapper = it; s.wrapperValue("ddrawrapper", it) }
                } else {
                    SettingsDivider()
                    SettingToggle(uiStr(R.string.ui_csmt), s.csmt) { s.csmt = it; s.wrapperValue("csmt", if (it) "3" else "0") }
                    SettingsDivider()
                    SettingToggle(uiStr(R.string.strict_shader_math), s.strictShaderMath) { s.strictShaderMath = it; s.wrapperValue("strict_shader_math", if (it) "1" else "0") }
                    SettingsDivider()
                    SettingChoice(uiStr(R.string.offscreen_rendering_mode), s.offscreenMode, listOf("fbo", "backbuffer")) { s.offscreenMode = it; s.wrapperValue("OffscreenRenderingMode", it) }
                    SettingsDivider()
                    SettingChoice(uiStr(R.string.ui_wine_renderer), s.wineRenderer, listOf("vulkan", "gl")) { s.wineRenderer = it; s.wrapperValue("renderer", it) }
                    SettingsDivider()
                    SettingText(uiStr(R.string.ui_video_memory), s.videoMemory) { s.videoMemory = it.filter(Char::isDigit).take(6); s.wrapperValue("videoMemorySize", s.videoMemory) }
                }
            }
            SettingsCard {
                if (s.arm64) {
                    SettingChoice(uiStr(R.string.ui_32_bit_emulator), s.emulator, listOf("FEXCore", "WOWBox64")) {
                        s.emulator = it; s.extra("emulator", if (it == "FEXCore") "FEXCore" else "Box64")
                    }
                    catalog?.let { c ->
                        SettingsDivider()
                        SettingInstallChoice(uiStr(R.string.fexcore_version), s.fexVersion, c.fex, s.installing, "FEXCore",
                            { v -> installRuntime("FEXCore", v) { installed -> s.fexVersion = installed; s.extra("fexcoreVersion", installed) } }) {
                            s.fexVersion = it; s.extra("fexcoreVersion", it)
                        }
                    }
                    SettingsDivider()
                    SettingMappedChoice(uiStr(R.string.fexcore_preset), s.fexPreset, fexPresets) { s.fexPreset = it; s.extra("fexcorePreset", it) }
                    SettingsDivider()
                    val ssbsDefault = uiStr(R.string.ui_container_default_1_s, uiOptionLabel(if (s.container.isForceSsbs) "On" else "Off"))
                    val ssbsOptions = listOf(ssbsDefault, "On", "Off")
                    SettingChoice(uiStr(R.string.ui_force_ssbs_cpu_speed_fix), when (s.forceSsbs) {
                        "1" -> "On"; "0" -> "Off"; else -> ssbsDefault
                    }, ssbsOptions) {
                        s.forceSsbs = when (it) { "On" -> "1"; "Off" -> "0"; else -> "" }
                        s.extra("forceSsbs", s.forceSsbs.ifEmpty { null })
                    }
                }
                if (!s.arm64 || s.emulator == "WOWBox64") {
                    catalog?.let { c ->
                        val versionCatalog = if (s.arm64) c.wow else c.box
                        val type = if (s.arm64) "WOWBox64" else "Box64"
                        SettingsDivider()
                        SettingInstallChoice(uiStr(R.string.ui_1_s_version, type), s.boxVersion, versionCatalog, s.installing, type,
                            { v -> installRuntime(type, v) { installed -> s.boxVersion = installed; s.extra("box64Version", installed) } }) {
                            s.boxVersion = it; s.extra("box64Version", it)
                        }
                    }
                    SettingsDivider()
                    SettingMappedChoice(uiStr(R.string.box64_preset), s.boxPreset, boxPresets) { s.boxPreset = it; s.extra("box64Preset", it) }
                }
            }
        }

        "Input" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                SettingMappedChoice(uiStr(R.string.ui_controls_profile), s.controlsProfile, profiles) { s.controlsProfile = it; s.extra("controlsProfile", it.takeUnless { id -> id == "0" }) }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_exclusive_input), s.exclusive) {
                    s.exclusive = it
                    if (!it) { s.xinput = true; s.dinput = true } else if (s.xinput && s.dinput) s.dinput = false
                    s.saveInput()
                }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_enable_xinput), s.xinput, s.exclusive) { s.xinput = it; if (s.exclusive && it && s.dinput) s.dinput = false; s.saveInput() }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_enable_dinput), s.dinput, s.exclusive) { s.dinput = it; if (s.exclusive && it && s.xinput) s.xinput = false; s.saveInput() }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_relative_mouse), s.relativeMouse) { s.relativeMouse = it; s.extra("enableRelativeMouse", if (it) "1" else null) }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_disable_mouse), s.disableMouse) { s.disableMouse = it; s.extra("disableMouse", if (it) "1" else null) }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_simulated_touchscreen), s.simulatedTouch) { s.simulatedTouch = it; s.extra("simTouchScreen", if (it) "1" else "0") }
            }
        }

        "Environment" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EnvironmentVariablesEditor(s.envVars, onChanged = { s.setEnvironment(it) })
        }

        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                val startupEntries = listOf(
                    "Normal (Load all services)",
                    "Essential (Load only essential services)",
                    "Aggressive (Stop services on startup)"
                )
                SettingChoice(uiStr(R.string.startup_selection), startupEntries[s.startup], startupEntries) {
                    s.startup = startupEntries.indexOf(it).coerceAtLeast(0); s.extra("startupSelection", s.startup.toString())
                }
            }
            SettingsCard {
                SettingToggle(uiStr(R.string.ui_sync_cpu_topology), s.syncCpu) { s.syncCpu = it; s.saveCpu() }
                SettingsDivider()
                CpuSelectorRow(uiStr(R.string.processor_affinity), s.cpu) { index, checked -> s.cpu[index] = checked; s.saveCpu() }
            }
            com.winlator.cmod.ui.settings.CodecsSettings(s.builtinDecoder, s.softwareDecoding,
                { s.builtinDecoder = it; s.extra("builtinDecoder", it) },
                { s.softwareDecoding = it; s.extra("softwareDecoding", if (it) "1" else "0") })
            SettingsCard { com.winlator.cmod.components.WindowsComponentsEntry(s.container) }
            SettingsCard {
                SettingChoice(uiStr(R.string.vkbasalt_sharpness_effects), s.sharpnessEffect, listOf("None", "CAS", "DLS")) {
                    s.sharpnessEffect = it; s.extra("sharpnessEffect", it)
                }
                SettingsDivider()
                SharpnessSliderV2(uiStr(R.string.vkbasalt_sharpness_level), s.sharpnessLevel) {
                    s.sharpnessLevel = it; s.extra("sharpnessLevel", it)
                }
                SettingsDivider()
                SharpnessSliderV2(uiStr(R.string.ui_sharpness_denoise), s.sharpnessDenoise) {
                    s.sharpnessDenoise = it; s.extra("sharpnessDenoise", it)
                }
            }
            ExecArgumentsEditorV2(s.execArgs) {
                s.execArgs = it; s.extra("execArgs", it.ifBlank { null })
            }
            SettingsCard {
                shortcutComponentRowsV2.forEachIndexed { index, (key, label) ->
                    val entries = listOf("Builtin (Wine)", "Native (Windows)")
                    val selected = entries[(s.components[key] ?: 0).coerceIn(0, 1)]
                    SettingChoice(label, selected, entries) {
                        s.components[key] = entries.indexOf(it).coerceAtLeast(0); s.saveComponents()
                    }
                    if (index != shortcutComponentRowsV2.lastIndex) SettingsDivider()
                }
            }
        }
    }
}

@Composable
private fun SharpnessSliderV2(label: String, value: String, onChanged: (String) -> Unit) {
    val numeric = value.toFloatOrNull()?.coerceIn(0f, 100f) ?: 100f
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(numeric.roundToInt().toString(), fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = numeric,
            onValueChange = { onChanged(it.roundToInt().coerceIn(0, 100).toString()) },
            valueRange = 0f..100f
        )
    }
}

@Composable
private fun ExecArgumentsEditorV2(value: String, onChanged: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onChanged,
                label = { Text(uiStr(R.string.exec_arguments)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )
            Box {
                TextButton(onClick = { open = true }) { Text("⋮", style = MaterialTheme.typography.headlineSmall) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    execArgumentPresetsV2.forEach { arg ->
                        DropdownMenuItem(
                            text = { Text(arg) },
                            onClick = {
                                if (!value.split(' ').contains(arg)) onChanged(listOf(value.trim(), arg).filter(String::isNotBlank).joinToString(" "))
                                open = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutVulkanExtensionsV2(context: Context, driver: String, blacklisted: String, onChanged: (String) -> Unit) {
    val extensions = remember(driver) { runCatching { GPUInformation.enumerateExtensions(driver, context).toList().sorted() }.getOrDefault(emptyList()) }
    if (extensions.isEmpty()) {
        SettingText(uiStr(R.string.ui_disabled_vulkan_extensions), blacklisted, 2) { onChanged(it.replace(" ", "")) }
        return
    }
    val disabled = blacklisted.split(',').map(String::trim).filter(String::isNotBlank).toSet()
    val enabledCount = extensions.count { it !in disabled }
    var open by remember { mutableStateOf(false) }
    Surface(onClick = { open = true }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(uiStr(R.string.ui_vulkan_extensions), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(uiStr(R.string.ui_1_s_of_2_s_enabled, enabledCount, extensions.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        }
    }
    if (open) {
        val selected = remember(blacklisted, open, extensions) { mutableStateListOf<String>().apply { addAll(extensions.filter { it !in disabled }) } }
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = { onChanged(extensions.filterNot { it in selected }.joinToString(",")); open = false }) { Text(uiStr(R.string.ui_done)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(uiStr(R.string.cancel)) } },
            title = { Text(uiStr(R.string.ui_vulkan_extensions)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(extensions) { extension ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = extension in selected,
                                onCheckedChange = { checked -> if (checked) { if (extension !in selected) selected.add(extension) } else selected.remove(extension) }
                            )
                            Text(extension, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                        }
                    }
                }
            }
        )
    }
}

private fun loadShortcutGpuNamesV2(context: Context): List<String> {
    val result = linkedSetOf("Device")
    runCatching {
        val data = FileUtils.readString(context, "gpu_cards.json")
        val array = JSONArray(data)
        for (index in 0 until array.length()) {
            val name = array.optJSONObject(index)?.optString("name").orEmpty()
            if (name.isNotBlank()) result.add(name)
        }
    }
    return result.toList()
}

private fun loadShortcutSoundFontsV2(context: Context): List<String> {
    val result = linkedSetOf("Disabled", MidiManager.DEFAULT_SF2_FILE)
    MidiManager.getSoundFontDir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }?.forEach { result.add(it.name) }
    return result.toList()
}

private fun parseShortcutComponentsV2(raw: String): Map<String, Int> {
    val result = mutableMapOf<String, Int>()
    raw.split(',').forEach { token ->
        val split = token.indexOf('=')
        if (split > 0) result[token.substring(0, split)] = token.substring(split + 1).toIntOrNull()?.coerceIn(0, 1) ?: 0
    }
    shortcutComponentRowsV2.forEach { (key, _) -> result.putIfAbsent(key, 0) }
    return result
}

private fun renameShortcutV2(shortcut: Shortcut, requested: String) {
    val clean = requested.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    if (clean.isBlank() || clean == shortcut.name) return

    val parent = shortcut.file.parentFile
    val oldName = shortcut.name
    val ext = shortcut.file.extension.takeIf { it.isNotBlank() } ?: "desktop"
    val target = File(parent, "$clean.$ext")
    if (target.exists() || !shortcut.file.renameTo(target)) return

    val oldLink = File(parent, "$oldName.lnk")
    if (oldLink.isFile) {
        val newLink = File(parent, "$clean.lnk")
        if (!newLink.exists()) oldLink.renameTo(newLink)
    }
}
