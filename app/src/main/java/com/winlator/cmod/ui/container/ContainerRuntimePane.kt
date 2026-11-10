package com.winlator.cmod.ui.container

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.box64.Box64PresetManager
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.core.StringUtils
import com.winlator.cmod.fexcore.FEXCorePresetManager
import com.winlator.cmod.ui.settings.DriverOption
import com.winlator.cmod.ui.settings.SettingChoice
import com.winlator.cmod.ui.settings.SettingDriverChoice
import com.winlator.cmod.ui.settings.SettingInstallChoice
import com.winlator.cmod.ui.settings.SettingMappedChoice
import com.winlator.cmod.ui.settings.SettingText
import com.winlator.cmod.ui.settings.SettingToggle
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsCatalog
import com.winlator.cmod.ui.settings.SettingsDivider
import com.winlator.cmod.ui.settings.installAdrenoDriver
import com.winlator.cmod.ui.settings.installRuntimeComponent
import com.winlator.cmod.ui.settings.loadSettingsCatalog
import com.winlator.cmod.ui.settings.normalizeResolution
import com.winlator.cmod.ui.settings.readConfig
import com.winlator.cmod.ui.settings.writeConfig
import kotlinx.coroutines.launch

@Composable
internal fun ContainerRuntimePane(
    containerId: Int,
    section: String
) {
    val context = LocalContext.current
    val container = remember(containerId) { ContainerManager(context).getContainerById(containerId) } ?: return
    val arm64 = remember(container.getWineVersion()) { container.getWineVersion().contains("arm64ec", true) }
    val screenEntries = remember { context.resources.getStringArray(R.array.screen_size_entries).toList() }
    val graphicsEntries = remember { context.resources.getStringArray(R.array.graphics_driver_entries).toList() }
    val audioEntries = remember { context.resources.getStringArray(R.array.audio_driver_entries).toList() }
    val wrapperEntries = remember { context.resources.getStringArray(R.array.dxwrapper_entries).toList() }
    val fexPresets = remember { FEXCorePresetManager.getPresets(context).associate { it.id to it.name } }
    val boxPresets = remember { Box64PresetManager.getPresets("box64", context).associate { it.id to it.name } }

    var displayServer by remember(containerId) {
        mutableStateOf(if (container.getExtra("displayServer", "java") == "native")
            "Ludashi X11 (Native Vulkan)" else "Java X11")
    }
    var renderer by remember { mutableStateOf(if (container.isRendererNative()) "EGL" else "Vulkan") }
    var screen by remember { mutableStateOf(normalizeResolution(container.getScreenSize())) }
    var screenChoice by remember {
        mutableStateOf(screenEntries.firstOrNull { normalizeResolution(it).equals(screen, true) } ?: "Custom")
    }
    var graphics by remember { mutableStateOf(graphicsEntries.firstOrNull() ?: "Wrapper") }
    var graphicsConfig by remember { mutableStateOf(container.getGraphicsDriverConfig()) }
    var driverVersion by remember { mutableStateOf(readConfig(graphicsConfig, "version", ';').ifBlank { "System" }) }
    var vulkanVersion by remember { mutableStateOf(readConfig(graphicsConfig, "vulkanVersion", ';').ifBlank { "1.3" }) }
    var maxDeviceMemory by remember { mutableStateOf(readConfig(graphicsConfig, "maxDeviceMemory", ';').ifBlank { "0" }) }
    var graphicsPresentMode by remember { mutableStateOf(readConfig(graphicsConfig, "presentMode", ';').ifBlank { "mailbox" }) }
    var syncFrame by remember { mutableStateOf(readConfig(graphicsConfig, "syncFrame", ';') == "1") }
    var disablePresentWait by remember { mutableStateOf(readConfig(graphicsConfig, "disablePresentWait", ';') == "1") }
    var resourceType by remember { mutableStateOf(readConfig(graphicsConfig, "resourceType", ';').ifBlank { "auto" }) }
    var bcnEmulation by remember { mutableStateOf(readConfig(graphicsConfig, "bcnEmulation", ';').ifBlank { "auto" }) }
    var bcnType by remember { mutableStateOf(readConfig(graphicsConfig, "bcnEmulationType", ';').ifBlank { "compute" }) }
    var bcnCache by remember { mutableStateOf(readConfig(graphicsConfig, "bcnEmulationCache", ';') == "1") }
    var gpuName by remember { mutableStateOf(readConfig(graphicsConfig, "gpuName", ';').ifBlank { "Device" }) }
    var blacklistedExtensions by remember { mutableStateOf(readConfig(graphicsConfig, "blacklistedExtensions", ';')) }

    var presentMode by remember { mutableStateOf(container.getRendererPresentMode()) }
    var rendererDriverId by remember { mutableStateOf(container.getRendererDriverId()) }
    var filterMode by remember { mutableStateOf(container.getRendererFilterMode()) }
    var swapRB by remember { mutableStateOf(container.getRendererSwapRB()) }
    var audio by remember { mutableStateOf(StringUtils.parseIdentifier(container.getAudioDriver())) }

    var wrapper by remember { mutableStateOf(StringUtils.parseIdentifier(container.getDXWrapper())) }
    var wrapperConfig by remember { mutableStateOf(container.getDXWrapperConfig()) }
    var dxvkVersion by remember { mutableStateOf(readConfig(wrapperConfig, "version", ',').ifBlank { "" }) }
    var vkd3dVersion by remember { mutableStateOf(readConfig(wrapperConfig, "vkd3dVersion", ',').ifBlank { "None" }) }
    var vkd3dLevel by remember { mutableStateOf(readConfig(wrapperConfig, "vkd3dLevel", ',').ifBlank { "12_1" }) }
    var frameRate by remember { mutableStateOf(readConfig(wrapperConfig, "framerate", ',').ifBlank { "0" }) }
    var maxFrameLatency by remember { mutableStateOf(readConfig(wrapperConfig, "maxFrameLatency", ',') == "1") }
    var async by remember { mutableStateOf(readConfig(wrapperConfig, "async", ',') == "1") }
    var asyncCache by remember { mutableStateOf(readConfig(wrapperConfig, "asyncCache", ',') == "1") }
    var ddrawWrapper by remember { mutableStateOf(readConfig(wrapperConfig, "ddrawrapper", ',').ifBlank { "wined3d" }) }
    var csmt by remember { mutableStateOf(readConfig(wrapperConfig, "csmt", ',') != "0") }
    var strictShaderMath by remember { mutableStateOf(readConfig(wrapperConfig, "strict_shader_math", ',') == "1") }
    var offscreenMode by remember { mutableStateOf(readConfig(wrapperConfig, "OffscreenRenderingMode", ',').ifBlank { "fbo" }) }
    var wineRenderer by remember { mutableStateOf(readConfig(wrapperConfig, "renderer", ',').ifBlank { "vulkan" }) }
    var videoMemory by remember { mutableStateOf(readConfig(wrapperConfig, "videoMemorySize", ',').ifBlank { "2048" }) }

    var emulator by remember {
        mutableStateOf(if (!arm64) "Box64" else if (container.getEmulator().equals("FEXCore", true)) "FEXCore" else "WOWBox64")
    }
    var fexVersion by remember { mutableStateOf(container.getFEXCoreVersion().orEmpty()) }
    var boxVersion by remember { mutableStateOf(container.getBox64Version().orEmpty()) }
    var fexPreset by remember { mutableStateOf(container.getFEXCorePreset()) }
    var boxPreset by remember { mutableStateOf(container.getBox64Preset()) }

    var revision by remember { mutableStateOf(0) }
    var installing by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val catalog by produceState<SettingsCatalog?>(null, arm64, revision) {
        value = loadSettingsCatalog(context, arm64, dxvkVersion, vkd3dVersion, fexVersion, boxVersion, driverVersion)
    }

    fun saveGraphics(key: String, value: String) {
        val updated = writeConfig(graphicsConfig, key, value, ';')
        graphicsConfig = updated
        container.setGraphicsDriverConfig(updated)
        container.saveData()
    }
    fun saveWrapper(key: String, value: String) {
        val updated = writeConfig(wrapperConfig, key, value, ',')
        wrapperConfig = updated
        container.setDXWrapperConfig(updated)
        container.saveData()
    }
    fun installRuntime(type: String, version: String, done: (String) -> Unit) {
        val key = "$type:$version"
        if (key in installing) return
        installing = installing + key
        scope.launch {
            val installed = installRuntimeComponent(context, type, version)
            installing = installing - key
            if (installed == null) Toast.makeText(context, "Unable to install $version", Toast.LENGTH_SHORT).show()
            else { done(installed); revision++ }
        }
    }
    fun installDriver(option: DriverOption) {
        val key = "driver:${option.remoteUrl ?: option.id}"
        if (key in installing) return
        installing = installing + key
        scope.launch {
            val installed = installAdrenoDriver(context, option)
            installing = installing - key
            if (installed == null) Toast.makeText(context, "Unable to install ${option.label}", Toast.LENGTH_SHORT).show()
            else { driverVersion = installed; saveGraphics("version", installed); revision++ }
        }
    }

    when (section) {
        ContainerOverviewComposeHost.SECTION_AUDIO -> SettingsCard {
            SettingChoice("Audio Driver", audioEntries.firstOrNull { StringUtils.parseIdentifier(it).equals(audio, true) } ?: audio, audioEntries) {
                audio = StringUtils.parseIdentifier(it)
                container.setAudioDriver(audio)
                container.saveData()
            }
        }

        ContainerOverviewComposeHost.SECTION_VIDEO -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                SettingChoice("Display Server", displayServer,
                    listOf("Java X11", "Ludashi X11 (Native Vulkan)")) {
                    displayServer = it
                    container.putExtra("displayServer", if (it.startsWith("Ludashi")) "native" else "java")
                    container.saveData()
                }
                if (displayServer == "Java X11") {
                SettingsDivider()
                SettingChoice("Renderer", renderer, listOf("Vulkan", "EGL")) {
                    renderer = it; container.setRendererNative(it == "EGL"); container.saveData()
                }
                SettingsDivider()
                SettingChoice("Present Mode", presentMode, listOf("mailbox", "fifo")) {
                    presentMode = it; container.setRendererPresentMode(it); container.saveData()
                }
                catalog?.let { c ->
                    SettingsDivider()
                    SettingMappedChoice("Renderer Driver", rendererDriverId, c.rendererDrivers) {
                        rendererDriverId = it; container.setRendererDriverId(it); container.saveData()
                    }
                }
                SettingsDivider()
                val filters = if (renderer == "EGL") listOf("Bilinear", "Nearest neighbor") else listOf("Bilinear", "Nearest neighbor", "Snapdragon Super Resolution", "AMD FidelityFX Super Resolution")
                SettingChoice("Texture Filter", filters.getOrElse(filterMode) { filters.first() }, filters) {
                    filterMode = filters.indexOf(it).coerceAtLeast(0); container.setRendererFilterMode(filterMode); container.saveData()
                }
                SettingsDivider()
                SettingToggle("Swap red/blue channels", swapRB) { swapRB = it; container.setRendererSwapRB(it); container.saveData() }
                } else {
                    Text("Native X11 protocol with the Ludashi Vulkan renderer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SettingsCard {
                SettingChoice("Screen Size", screenChoice, screenEntries) {
                    screenChoice = it
                    if (!it.equals("Custom", true)) {
                        screen = normalizeResolution(it); container.setScreenSize(screen); container.saveData()
                    }
                }
                if (screenChoice.equals("Custom", true)) {
                    SettingsDivider(); SettingText("Custom resolution", screen) {
                        screen = it
                        if (Regex("\\d{2,5}x\\d{2,5}", RegexOption.IGNORE_CASE).matches(it.trim())) {
                            container.setScreenSize(normalizeResolution(it)); container.saveData()
                        }
                    }
                }
            }
            SettingsCard {
                SettingChoice("Graphics Driver", graphics, graphicsEntries) {
                    graphics = it; container.setGraphicsDriver(StringUtils.parseIdentifier(it)); container.saveData()
                }
                catalog?.let { c ->
                    SettingsDivider(); SettingDriverChoice("Driver Version", driverVersion, c.drivers, installing, ::installDriver) {
                        driverVersion = it; saveGraphics("version", it)
                    }
                }
                SettingsDivider(); SettingChoice("Vulkan Version", vulkanVersion, listOf("1.1", "1.2", "1.3")) { vulkanVersion = it; saveGraphics("vulkanVersion", it) }
                SettingsDivider(); SettingChoice("Max Device Memory", maxDeviceMemory, listOf("0", "512", "1024", "2048", "4096", "8192", "12288", "16384")) { maxDeviceMemory = it; saveGraphics("maxDeviceMemory", it) }
                SettingsDivider(); SettingChoice("Driver Present Mode", graphicsPresentMode, listOf("mailbox", "fifo", "immediate", "relaxed")) { graphicsPresentMode = it; saveGraphics("presentMode", it) }
                SettingsDivider(); SettingToggle("Sync Frame", syncFrame) { syncFrame = it; saveGraphics("syncFrame", if (it) "1" else "0") }
                SettingsDivider(); SettingToggle("Disable Present Wait", disablePresentWait) { disablePresentWait = it; saveGraphics("disablePresentWait", if (it) "1" else "0") }
                SettingsDivider(); SettingChoice("Resource Type", resourceType, listOf("auto", "dmabuf", "ahb", "opaque")) { resourceType = it; saveGraphics("resourceType", it) }
                SettingsDivider(); SettingChoice("BCN Emulation", bcnEmulation, listOf("none", "partial", "full", "auto")) { bcnEmulation = it; saveGraphics("bcnEmulation", it) }
                SettingsDivider(); SettingChoice("BCN Emulation Type", bcnType, listOf("software", "compute")) { bcnType = it; saveGraphics("bcnEmulationType", it) }
                SettingsDivider(); SettingToggle("BCN Emulation Cache", bcnCache) { bcnCache = it; saveGraphics("bcnEmulationCache", if (it) "1" else "0") }
                SettingsDivider(); SettingText("GPU Name", gpuName) { gpuName = it; saveGraphics("gpuName", it) }
                SettingsDivider(); SettingText("Blacklisted Extensions", blacklistedExtensions, 2) { blacklistedExtensions = it; saveGraphics("blacklistedExtensions", it.replace(" ", "")) }
            }
        }

        ContainerOverviewComposeHost.SECTION_COMPATIBILITY -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                val shownWrapper = wrapperEntries.firstOrNull { StringUtils.parseIdentifier(it).equals(wrapper, true) } ?: wrapper
                SettingChoice("DX Wrapper", shownWrapper, wrapperEntries) { wrapper = StringUtils.parseIdentifier(it); container.setDXWrapper(wrapper); container.saveData() }
                if (wrapper.contains("dxvk", true)) {
                    catalog?.let { c ->
                        SettingsDivider(); SettingInstallChoice("DXVK Version", dxvkVersion, c.dxvk, installing, "DXVK", { v -> installRuntime("DXVK", v) { dxvkVersion = it; saveWrapper("version", it) } }) { dxvkVersion = it; saveWrapper("version", it) }
                        SettingsDivider(); SettingInstallChoice("VKD3D Version", vkd3dVersion, c.vkd3d, installing, "VKD3D", { v -> installRuntime("VKD3D", v) { vkd3dVersion = it; saveWrapper("vkd3dVersion", it) } }) { vkd3dVersion = it; saveWrapper("vkd3dVersion", it) }
                    }
                    SettingsDivider(); SettingChoice("VKD3D Feature Level", vkd3dLevel, listOf("12_0", "12_1", "12_2")) { vkd3dLevel = it; saveWrapper("vkd3dLevel", it) }
                    SettingsDivider(); SettingText("Frame Rate", frameRate) { frameRate = it.filter(Char::isDigit).take(4); saveWrapper("framerate", frameRate.ifBlank { "0" }) }
                    SettingsDivider(); SettingToggle("Max Frame Latency", maxFrameLatency) { maxFrameLatency = it; saveWrapper("maxFrameLatency", if (it) "1" else "0") }
                    SettingsDivider(); SettingToggle("Async", async) { async = it; saveWrapper("async", if (it) "1" else "0") }
                    SettingsDivider(); SettingToggle("Async Cache", asyncCache) { asyncCache = it; saveWrapper("asyncCache", if (it) "1" else "0") }
                    SettingsDivider(); SettingChoice("DDraw Wrapper", ddrawWrapper, listOf("wined3d", "cnc-ddraw", "dd7to9", "none")) { ddrawWrapper = it; saveWrapper("ddrawrapper", it) }
                } else {
                    SettingsDivider(); SettingToggle("CSMT", csmt) { csmt = it; saveWrapper("csmt", if (it) "3" else "0") }
                    SettingsDivider(); SettingToggle("Strict Shader Math", strictShaderMath) { strictShaderMath = it; saveWrapper("strict_shader_math", if (it) "1" else "0") }
                    SettingsDivider(); SettingChoice("Offscreen Rendering Mode", offscreenMode, listOf("fbo", "backbuffer")) { offscreenMode = it; saveWrapper("OffscreenRenderingMode", it) }
                    SettingsDivider(); SettingChoice("Wine Renderer", wineRenderer, listOf("vulkan", "gl")) { wineRenderer = it; saveWrapper("renderer", it) }
                    SettingsDivider(); SettingText("Video Memory", videoMemory) { videoMemory = it.filter(Char::isDigit).take(6); saveWrapper("videoMemorySize", videoMemory) }
                }
            }
            SettingsCard {
                Text("Runtime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                SettingsDivider()
                if (arm64) {
                    SettingChoice("32-bit Emulator", emulator, listOf("FEXCore", "WOWBox64")) {
                        emulator = it; container.setEmulator(if (it == "FEXCore") "FEXCore" else "Box64"); container.saveData()
                    }
                    catalog?.let { c ->
                        SettingsDivider(); SettingInstallChoice("FEXCore Version", fexVersion, c.fex, installing, "FEXCore", { v -> installRuntime("FEXCore", v) { fexVersion = it; container.setFEXCoreVersion(it); container.saveData() } }) { fexVersion = it; container.setFEXCoreVersion(it); container.saveData() }
                    }
                    SettingsDivider(); SettingMappedChoice("FEXCore Preset", fexPreset, fexPresets) { fexPreset = it; container.setFEXCorePreset(it); container.saveData() }
                }
                if (!arm64 || emulator == "WOWBox64") {
                    catalog?.let { c ->
                        val versions = if (arm64) c.wow else c.box; val type = if (arm64) "WOWBox64" else "Box64"
                        SettingsDivider(); SettingInstallChoice("$type Version", boxVersion, versions, installing, type, { v -> installRuntime(type, v) { boxVersion = it; container.setBox64Version(it); container.saveData() } }) { boxVersion = it; container.setBox64Version(it); container.saveData() }
                    }
                    SettingsDivider(); SettingMappedChoice("Box64 Preset", boxPreset, boxPresets) { boxPreset = it; container.setBox64Preset(it); container.saveData() }
                }
            }
        }
    }
}

