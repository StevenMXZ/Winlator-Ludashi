package com.winlator.cmod.ui.container

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.ui.theme.WinZTheme

@Stable
interface ContainerSectionCallbacks {
    fun onManageComponents()
    fun onInstallComponent(type: String, version: String)

    fun onSave(
        renderer: String,
        displayServer: String,
        screenSize: String,
        graphicsDriver: String,
        graphicsDriverConfig: String,
        audioDriver: String,
        wrapper: String,
        rendererPresentMode: String,
        rendererDriverId: String,
        rendererFilterMode: Int,
        rendererSwapRB: Boolean,
        wrapperConfig: String,
        emulator: String,
        box64Version: String,
        box64Preset: String,
        fexcoreVersion: String,
        fexcorePreset: String
    )
}

object ContainerSectionComposeHost {
    @JvmStatic
    fun create(
        context: Context,
        container: com.winlator.cmod.container.Container,
        section: Int,
        description: String,
        rendererEntries: Array<String>,
        selectedRenderer: String,
        selectedDisplayServer: String,
        screenEntries: Array<String>,
        selectedScreen: String,
        customScreenSize: String,
        graphicsEntries: Array<String>,
        selectedGraphics: String,
        graphicsVersionEntries: Array<String>,
        installedGraphicsVersionEntries: Array<String>,
        graphicsDriverConfig: String,
        defaultGraphicsVersion: String,
        audioEntries: Array<String>,
        selectedAudio: String,
        wrapperEntries: Array<String>,
        selectedWrapper: String,
        dxvkEntries: Array<String>,
        installedDxvkEntries: Array<String>,
        vkd3dEntries: Array<String>,
        installedVkd3dEntries: Array<String>,
        wrapperConfig: String,
        rendererPresentMode: String,
        rendererDriverEntries: Array<String>,
        rendererDriverIds: Array<String>,
        selectedRendererDriverId: String,
        rendererFilterMode: Int,
        rendererSwapRB: Boolean,
        arm64EcWine: Boolean,
        emulatorEntries: Array<String>,
        selectedEmulator: String,
        box64VersionEntries: Array<String>,
        installedBox64VersionEntries: Array<String>,
        selectedBox64Version: String,
        box64PresetEntries: Array<String>,
        box64PresetIds: Array<String>,
        selectedBox64Preset: String,
        fexcoreVersionEntries: Array<String>,
        installedFexcoreVersionEntries: Array<String>,
        selectedFexcoreVersion: String,
        fexcorePresetEntries: Array<String>,
        fexcorePresetIds: Array<String>,
        selectedFexcorePreset: String,
        callbacks: ContainerSectionCallbacks
    ): ComposeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            WinZTheme {
                ContainerSectionScreen(
                    settingsContainer = container,
                    section = section,
                    description = description,
                    rendererEntries = rendererEntries,
                    initialRenderer = selectedRenderer,
                    initialDisplayServer = selectedDisplayServer,
                    screenEntries = screenEntries,
                    initialScreen = selectedScreen,
                    initialCustomScreen = customScreenSize,
                    graphicsEntries = graphicsEntries,
                    initialGraphics = selectedGraphics,
                    graphicsVersionEntries = graphicsVersionEntries,
                    installedGraphicsVersionEntries = installedGraphicsVersionEntries,
                    initialGraphicsDriverConfig = graphicsDriverConfig,
                    defaultGraphicsVersion = defaultGraphicsVersion,
                    audioEntries = audioEntries,
                    initialAudio = selectedAudio,
                    wrapperEntries = wrapperEntries,
                    initialWrapper = selectedWrapper,
                    dxvkEntries = dxvkEntries,
                    installedDxvkEntries = installedDxvkEntries,
                    vkd3dEntries = vkd3dEntries,
                    installedVkd3dEntries = installedVkd3dEntries,
                    initialWrapperConfig = wrapperConfig,
                    initialPresentMode = rendererPresentMode,
                    rendererDriverEntries = rendererDriverEntries,
                    rendererDriverIds = rendererDriverIds,
                    initialRendererDriverId = selectedRendererDriverId,
                    initialFilterMode = rendererFilterMode,
                    initialSwapRB = rendererSwapRB,
                    arm64EcWine = arm64EcWine,
                    emulatorEntries = emulatorEntries,
                    initialEmulator = selectedEmulator,
                    box64VersionEntries = box64VersionEntries,
                    installedBox64VersionEntries = installedBox64VersionEntries,
                    initialBox64Version = selectedBox64Version,
                    box64PresetEntries = box64PresetEntries,
                    box64PresetIds = box64PresetIds,
                    initialBox64Preset = selectedBox64Preset,
                    fexcoreVersionEntries = fexcoreVersionEntries,
                    installedFexcoreVersionEntries = installedFexcoreVersionEntries,
                    initialFexcoreVersion = selectedFexcoreVersion,
                    fexcorePresetEntries = fexcorePresetEntries,
                    fexcorePresetIds = fexcorePresetIds,
                    initialFexcorePreset = selectedFexcorePreset,
                    callbacks = callbacks
                )
            }
        }
    }
}

@Composable
private fun ContainerSectionScreen(
    settingsContainer: com.winlator.cmod.container.Container,
    section: Int,
    description: String,
    rendererEntries: Array<String>,
    initialRenderer: String,
    initialDisplayServer: String,
    screenEntries: Array<String>,
    initialScreen: String,
    initialCustomScreen: String,
    graphicsEntries: Array<String>,
    initialGraphics: String,
    graphicsVersionEntries: Array<String>,
    installedGraphicsVersionEntries: Array<String>,
    initialGraphicsDriverConfig: String,
    defaultGraphicsVersion: String,
    audioEntries: Array<String>,
    initialAudio: String,
    wrapperEntries: Array<String>,
    initialWrapper: String,
    dxvkEntries: Array<String>,
    installedDxvkEntries: Array<String>,
    vkd3dEntries: Array<String>,
    installedVkd3dEntries: Array<String>,
    initialWrapperConfig: String,
    initialPresentMode: String,
    rendererDriverEntries: Array<String>,
    rendererDriverIds: Array<String>,
    initialRendererDriverId: String,
    initialFilterMode: Int,
    initialSwapRB: Boolean,
    arm64EcWine: Boolean,
    emulatorEntries: Array<String>,
    initialEmulator: String,
    box64VersionEntries: Array<String>,
    installedBox64VersionEntries: Array<String>,
    initialBox64Version: String,
    box64PresetEntries: Array<String>,
    box64PresetIds: Array<String>,
    initialBox64Preset: String,
    fexcoreVersionEntries: Array<String>,
    installedFexcoreVersionEntries: Array<String>,
    initialFexcoreVersion: String,
    fexcorePresetEntries: Array<String>,
    fexcorePresetIds: Array<String>,
    initialFexcorePreset: String,
    callbacks: ContainerSectionCallbacks
) {
    var renderer by remember(initialRenderer) { mutableStateOf(initialRenderer) }
    var surfaceFormat by remember { mutableStateOf(settingsContainer.getSurfaceFormat()) }
    var displayXBypass by remember { mutableStateOf(settingsContainer.getTrueDisplayX()) }
    var displayXPerformance by remember { mutableStateOf(settingsContainer.getDisplayXPerformanceMode()) }
    var displayXRefresh by remember { mutableStateOf(settingsContainer.getDisplayXPresentAtRefreshRate()) }
    var displayXMode by remember { mutableStateOf(settingsContainer.getDisplayXPresentMode()) }
    var displayXRange by remember { mutableStateOf(settingsContainer.getDisplayXDynamicRange()) }
    var displayXPrecise by remember { mutableStateOf(settingsContainer.getDisplayXPrecisePresentation()) }
    var displayServer by remember(initialDisplayServer) { mutableStateOf(initialDisplayServer) }
    var screen by remember(initialScreen) { mutableStateOf(initialScreen) }
    var graphics by remember(initialGraphics) { mutableStateOf(initialGraphics) }
    val initialGraphicsValues = remember(initialGraphicsDriverConfig) { parseSemicolonConfig(initialGraphicsDriverConfig) }
    var graphicsOptionsExpanded by remember { mutableStateOf(false) }
    var graphicsVersion by remember(initialGraphicsDriverConfig) {
        mutableStateOf(initialGraphicsValues["version"].orEmpty().ifBlank { defaultGraphicsVersion })
    }
    var vulkanVersion by remember(initialGraphicsDriverConfig) { mutableStateOf(initialGraphicsValues["vulkanVersion"] ?: "1.3") }
    var graphicsPresentMode by remember(initialGraphicsDriverConfig) { mutableStateOf(initialGraphicsValues["presentMode"] ?: "mailbox") }
    var syncFrame by remember(initialGraphicsDriverConfig) { mutableStateOf(initialGraphicsValues["syncFrame"] == "1") }
    var disablePresentWait by remember(initialGraphicsDriverConfig) { mutableStateOf(initialGraphicsValues["disablePresentWait"] == "1") }
    var resourceType by remember(initialGraphicsDriverConfig) { mutableStateOf(initialGraphicsValues["resourceType"] ?: "auto") }
    var bcnEmulation by remember(initialGraphicsDriverConfig) { mutableStateOf(initialGraphicsValues["bcnEmulation"] ?: "auto") }
    var bcnEmulationType by remember(initialGraphicsDriverConfig) { mutableStateOf(initialGraphicsValues["bcnEmulationType"] ?: "compute") }
    var bcnEmulationCache by remember(initialGraphicsDriverConfig) { mutableStateOf(initialGraphicsValues["bcnEmulationCache"] == "1") }
    var audio by remember(initialAudio) { mutableStateOf(initialAudio) }
    var wrapper by remember(initialWrapper) { mutableStateOf(initialWrapper) }
    val customParts = remember(initialCustomScreen) { initialCustomScreen.split("x", limit = 2) }
    var customWidth by remember(initialCustomScreen) { mutableStateOf(customParts.getOrElse(0) { "1280" }) }
    var customHeight by remember(initialCustomScreen) { mutableStateOf(customParts.getOrElse(1) { "720" }) }
    var rendererOptionsExpanded by remember { mutableStateOf(false) }
    var presentMode by remember(initialPresentMode) { mutableStateOf(initialPresentMode) }
    var rendererDriverId by remember(initialRendererDriverId) { mutableStateOf(initialRendererDriverId) }
    var filterMode by remember(initialFilterMode) { mutableStateOf(initialFilterMode) }
    var swapRB by remember(initialSwapRB) { mutableStateOf(initialSwapRB) }
    val initialWrapperValues = remember(initialWrapperConfig) { parseConfig(initialWrapperConfig) }
    var wrapperOptionsExpanded by remember { mutableStateOf(false) }
    var dxvkVersion by remember(initialWrapperConfig) {
        mutableStateOf(initialWrapperValues["version"].orEmpty().ifBlank { DefaultVersion.DXVK })
    }
    var vkd3dVersion by remember(initialWrapperConfig) {
        mutableStateOf(initialWrapperValues["vkd3dVersion"].orEmpty().ifBlank { DefaultVersion.VKD3D })
    }
    var vkd3dLevel by remember(initialWrapperConfig) { mutableStateOf(initialWrapperValues["vkd3dLevel"] ?: "12_1") }
    var frameRate by remember(initialWrapperConfig) { mutableStateOf(initialWrapperValues["framerate"] ?: "0") }
    var maxFrameLatency by remember(initialWrapperConfig) { mutableStateOf(initialWrapperValues["maxFrameLatency"] == "1") }
    var async by remember(initialWrapperConfig) { mutableStateOf(initialWrapperValues["async"] == "1") }
    var asyncCache by remember(initialWrapperConfig) { mutableStateOf(initialWrapperValues["asyncCache"] == "1") }
    var ddrawWrapper by remember(initialWrapperConfig) { mutableStateOf(initialWrapperValues["ddrawrapper"] ?: "wined3d") }
    var csmt by remember(initialWrapperConfig) { mutableStateOf(initialWrapperValues["csmt"] != "0") }
    var strictShaderMath by remember(initialWrapperConfig) { mutableStateOf(initialWrapperValues["strict_shader_math"] == "1") }
    var offscreenMode by remember(initialWrapperConfig) { mutableStateOf(initialWrapperValues["OffscreenRenderingMode"] ?: "fbo") }
    var wineRenderer by remember(initialWrapperConfig) { mutableStateOf(initialWrapperValues["renderer"] ?: "vulkan") }
    var videoMemory by remember(initialWrapperConfig) { mutableStateOf(initialWrapperValues["videoMemorySize"] ?: "2048") }
    var emulator by remember(initialEmulator) { mutableStateOf(initialEmulator) }
    var box64Version by remember(initialBox64Version) { mutableStateOf(initialBox64Version) }
    var box64Preset by remember(initialBox64Preset) { mutableStateOf(initialBox64Preset) }
    var fexcoreVersion by remember(initialFexcoreVersion) { mutableStateOf(initialFexcoreVersion) }
    var fexcorePreset by remember(initialFexcorePreset) { mutableStateOf(initialFexcorePreset) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = {
                        val savedScreen = if (screen.equals("Custom", true)) {
                            "${customWidth.toIntOrNull()?.coerceAtLeast(1) ?: 1280}x${customHeight.toIntOrNull()?.coerceAtLeast(1) ?: 720}"
                        } else screen
                        if (section == 1) {
                            settingsContainer.setSurfaceFormat(surfaceFormat)
                            settingsContainer.setTrueDisplayX(displayXBypass)
                            settingsContainer.setDisplayXPerformanceMode(displayXPerformance)
                            settingsContainer.setDisplayXPresentAtRefreshRate(displayXRefresh)
                            settingsContainer.setDisplayXPresentMode(displayXMode)
                            settingsContainer.setDisplayXDynamicRange(displayXRange)
                        }
                        callbacks.onSave(
                            renderer,
                            displayServer,
                            savedScreen,
                            graphics,
                            mergeSemicolonConfig(
                                initialGraphicsDriverConfig,
                                mapOf(
                                    "version" to graphicsVersion,
                                    "vulkanVersion" to vulkanVersion,
                                    "presentMode" to graphicsPresentMode,
                                    "syncFrame" to if (syncFrame) "1" else "0",
                                    "disablePresentWait" to if (disablePresentWait) "1" else "0",
                                    "resourceType" to resourceType,
                                    "bcnEmulation" to bcnEmulation,
                                    "bcnEmulationType" to bcnEmulationType,
                                    "bcnEmulationCache" to if (bcnEmulationCache) "1" else "0"
                                )
                            ),
                            audio,
                            wrapper,
                            presentMode,
                            rendererDriverId,
                            filterMode,
                            swapRB,
                            mergeConfig(
                                initialWrapperConfig,
                                if (wrapper.lowercase().contains("dxvk")) mapOf(
                                    "version" to dxvkVersion,
                                    "vkd3dVersion" to vkd3dVersion,
                                    "vkd3dLevel" to vkd3dLevel,
                                    "framerate" to frameRate,
                                    "maxFrameLatency" to if (maxFrameLatency) "1" else "0",
                                    "async" to if (async) "1" else "0",
                                    "asyncCache" to if (asyncCache) "1" else "0",
                                    "ddrawrapper" to ddrawWrapper
                                ) else mapOf(
                                    "csmt" to if (csmt) "3" else "0",
                                    "strict_shader_math" to if (strictShaderMath) "1" else "0",
                                    "OffscreenRenderingMode" to offscreenMode,
                                    "renderer" to wineRenderer,
                                    "videoMemorySize" to videoMemory
                                )
                            ),
                            emulator,
                            box64Version,
                            box64Preset,
                            fexcoreVersion,
                            fexcorePreset
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .padding(
                            bottom = WindowInsets.navigationBars
                                .asPaddingValues()
                                .calculateBottomPadding()
                        )
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(uiStr(R.string.save), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 14.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 14.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "description") {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                )
            }
            item(key = "settings-group") {
                SettingsGroup {
                    when (section) {
                        1 -> {
                            ChoiceSetting(
                                Icons.Outlined.DesktopWindows,
                                uiStr(R.string.ui_display_server),
                                displayServer,
                                arrayOf("Java X11", "X++")
                            ) { displayServer = it }
                            GroupDivider()
                            ChoiceSetting(
                                Icons.Outlined.DesktopWindows,
                                uiStr(R.string.ui_renderer),
                                renderer,
                                rendererEntries
                            ) {
                                renderer = it
                                surfaceFormat = if (it == "DisplayX") "rgba8" else "bgra8"
                                if (it == "EGL" && filterMode > 1) filterMode = 0
                            }
                            GroupDivider()
                            ChoiceSetting(Icons.Outlined.Tune, uiStr(R.string.ui_surface_format),
                                if (surfaceFormat == "bgra8") "BGRA" else "RGBA", arrayOf("RGBA", "BGRA")) {
                                surfaceFormat = if (it == "BGRA") "bgra8" else "rgba8"
                            }
                            GroupDivider()
                            ActionSetting(
                                Icons.Outlined.Tune,
                                uiStr(R.string.ui_renderer_options),
                                uiStr(R.string.ui_present_mode_driver_filter_and_color_channels),
                                expanded = rendererOptionsExpanded
                            ) { rendererOptionsExpanded = !rendererOptionsExpanded }
                            if (rendererOptionsExpanded && renderer == "DisplayX") {
                                Column(Modifier.padding(start = 65.dp, end = 12.dp, bottom = 10.dp)) {
                                    InlineChoice(uiStr(R.string.ui_displayx_backend), if (displayXBypass) "DisplayX+" else "X11", arrayOf("X11", "DisplayX+")) { displayXBypass = it == "DisplayX+" }
                                    ToggleSetting(uiStr(R.string.ui_performance_mode), displayXPerformance) { displayXPerformance = it }
                                    ToggleSetting(uiStr(R.string.ui_present_at_refresh_rate), displayXRefresh) { displayXRefresh = it }
                                    InlineChoice(uiStr(R.string.ui_present_mode), displayXMode, arrayOf("auto", "mailbox", "fifo")) { displayXMode = it }
                                    if (displayXBypass) {
                                    InlineChoice(uiStr(R.string.ui_dynamic_range), displayXRange.uppercase(), arrayOf("SDR", "HDR")) { displayXRange = it.lowercase() }
                                    }
                                }
                            } else if (rendererOptionsExpanded) {
                                RendererOptionsPanel(
                                    nativeRenderer = !renderer.equals("Vulkan", true),
                                    presentMode = presentMode,
                                    onPresentMode = { presentMode = it },
                                    driverEntries = rendererDriverEntries,
                                    driverIds = rendererDriverIds,
                                    driverId = rendererDriverId,
                                    onDriverId = { rendererDriverId = it },
                                    filterMode = filterMode,
                                    onFilterMode = { filterMode = it },
                                    swapRB = swapRB,
                                    onSwapRB = { swapRB = it }
                                )
                            }
                            GroupDivider()
                            ChoiceSetting(Icons.Outlined.Memory, uiStr(R.string.screen_size), screen, screenEntries) { screen = it }
                            if (screen.equals("Custom", true)) {
                                CustomResolutionFields(
                                    width = customWidth,
                                    height = customHeight,
                                    onWidth = { customWidth = it.filter { char -> char.isDigit() }.take(5) },
                                    onHeight = { customHeight = it.filter { char -> char.isDigit() }.take(5) }
                                )
                            }
                            GroupDivider()
                            ChoiceSetting(
                                Icons.Outlined.DesktopWindows,
                                uiStr(R.string.graphics_driver),
                                graphics,
                                graphicsEntries
                            ) { graphics = it }
                            GroupDivider()
                            ActionSetting(
                                Icons.Outlined.Tune,
                                uiStr(R.string.ui_graphics_driver_options),
                                uiStr(R.string.ui_driver_version_vulkan_resource_and_bcn_settings),
                                expanded = graphicsOptionsExpanded
                            ) { graphicsOptionsExpanded = !graphicsOptionsExpanded }
                            if (graphicsOptionsExpanded) {
                                GraphicsDriverOptionsPanel(
                                    version = graphicsVersion,
                                    onVersion = { graphicsVersion = it },
                                    versionEntries = graphicsVersionEntries,
                                    installedVersionEntries = installedGraphicsVersionEntries,
                                    onInstallVersion = { callbacks.onInstallComponent("AdrenoTools", it) },
                                    vulkanVersion = vulkanVersion,
                                    onVulkanVersion = { vulkanVersion = it },
                                    presentMode = graphicsPresentMode,
                                    onPresentMode = { graphicsPresentMode = it },
                                    syncFrame = syncFrame,
                                    onSyncFrame = { syncFrame = it },
                                    disablePresentWait = disablePresentWait,
                                    onDisablePresentWait = { disablePresentWait = it },
                                    resourceType = resourceType,
                                    onResourceType = { resourceType = it },
                                    bcnEmulation = bcnEmulation,
                                    onBcnEmulation = { bcnEmulation = it },
                                    bcnEmulationType = bcnEmulationType,
                                    onBcnEmulationType = { bcnEmulationType = it },
                                    bcnEmulationCache = bcnEmulationCache,
                                    onBcnEmulationCache = { bcnEmulationCache = it }
                                )
                            }
                        }
                        2 -> {
                            ChoiceSetting(Icons.Outlined.VolumeUp, uiStr(R.string.audio_driver), audio, audioEntries) { audio = it }
                            GroupDivider()
                            InfoText(uiStr(R.string.ui_alsa_oboe_offers_direct_audio_output_pulseaudio_gn_is_available_f))
                        }
                        else -> {
                            ChoiceSetting(Icons.Outlined.VerifiedUser, uiStr(R.string.dxwrapper), wrapper, wrapperEntries) {
                                wrapper = it
                            }
                            GroupDivider()
                            ActionSetting(
                                Icons.Outlined.Tune,
                                uiStr(R.string.ui_wrapper_options),
                                uiStr(R.string.ui_dxvk_vkd3d_versions_and_wrapper_configuration),
                                expanded = wrapperOptionsExpanded
                            ) { wrapperOptionsExpanded = !wrapperOptionsExpanded }
                            if (wrapperOptionsExpanded) {
                                WrapperOptionsPanel(
                                    dxvk = wrapper.lowercase().contains("dxvk"),
                                    dxvkVersion = dxvkVersion,
                                    onDxvkVersion = { dxvkVersion = it },
                                    dxvkEntries = dxvkEntries,
                                    installedDxvkEntries = installedDxvkEntries,
                                    vkd3dVersion = vkd3dVersion,
                                    onVkd3dVersion = { vkd3dVersion = it },
                                    vkd3dEntries = vkd3dEntries,
                                    installedVkd3dEntries = installedVkd3dEntries,
                                    vkd3dLevel = vkd3dLevel,
                                    onVkd3dLevel = { vkd3dLevel = it },
                                    frameRate = frameRate,
                                    onFrameRate = { frameRate = it.filter { char -> char.isDigit() }.take(4) },
                                    maxFrameLatency = maxFrameLatency,
                                    onMaxFrameLatency = { maxFrameLatency = it },
                                    async = async,
                                    onAsync = { async = it },
                                    asyncCache = asyncCache,
                                    onAsyncCache = { asyncCache = it },
                                    ddrawWrapper = ddrawWrapper,
                                    onDdrawWrapper = { ddrawWrapper = it },
                                    csmt = csmt,
                                    onCsmt = { csmt = it },
                                    strictShaderMath = strictShaderMath,
                                    onStrictShaderMath = { strictShaderMath = it },
                                    offscreenMode = offscreenMode,
                                    onOffscreenMode = { offscreenMode = it },
                                    wineRenderer = wineRenderer,
                                    onWineRenderer = { wineRenderer = it },
                                    videoMemory = videoMemory,
                                    onVideoMemory = { videoMemory = it.filter { char -> char.isDigit() }.take(6) },
                                    onManageComponents = callbacks::onManageComponents,
                                    onInstallComponent = callbacks::onInstallComponent
                                )
                            }
                            GroupDivider()
                            ReadOnlySetting(
                                icon = Icons.Outlined.Memory,
                                label = uiStr(R.string.ui_64_bit_emulator),
                                value = if (arm64EcWine) "FEXCore" else "Box64"
                            )
                            GroupDivider()
                            if (arm64EcWine) {
                                ChoiceSetting(
                                    Icons.Outlined.Memory,
                                    uiStr(R.string.ui_32_bit_emulator),
                                    if (emulator.equals("FEXCore", true)) "FEXCore" else "WOWBox64",
                                    arrayOf("FEXCore", "WOWBox64")
                                ) { emulator = if (it.equals("WOWBox64", true)) "Box64" else "FEXCore" }
                            } else {
                                ReadOnlySetting(
                                    icon = Icons.Outlined.Memory,
                                    label = uiStr(R.string.ui_32_bit_emulator),
                                    value = "Box64"
                                )
                            }
                            if (arm64EcWine) {
                                GroupDivider()
                                ChoiceSetting(
                                    Icons.Outlined.Memory,
                                    uiStr(R.string.fexcore_version),
                                    fexcoreVersion,
                                    fexcoreVersionEntries,
                                    installedEntries = installedFexcoreVersionEntries,
                                    onInstall = { callbacks.onInstallComponent("FEXCore", it) }
                                ) { fexcoreVersion = it }
                                GroupDivider()
                                IdChoiceSetting(
                                    icon = Icons.Outlined.Tune,
                                    label = uiStr(R.string.fexcore_preset),
                                    selectedId = fexcorePreset,
                                    entries = fexcorePresetEntries,
                                    ids = fexcorePresetIds,
                                    onSelectedId = { fexcorePreset = it }
                                )
                            }
                            if (!arm64EcWine || !emulator.equals("FEXCore", true)) {
                                GroupDivider()
                                ChoiceSetting(
                                    Icons.Outlined.Memory,
                                    if (arm64EcWine) uiStr(R.string.ui_wowbox64_version) else uiStr(R.string.box64_version),
                                    box64Version,
                                    box64VersionEntries,
                                    installedEntries = installedBox64VersionEntries,
                                    onInstall = {
                                        callbacks.onInstallComponent(if (arm64EcWine) "WOWBox64" else "Box64", it)
                                    }
                                ) { box64Version = it }
                                GroupDivider()
                                IdChoiceSetting(
                                    icon = Icons.Outlined.Tune,
                                    label = uiStr(R.string.box64_preset),
                                    selectedId = box64Preset,
                                    entries = box64PresetEntries,
                                    ids = box64PresetIds,
                                    onSelectedId = { box64Preset = it }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IdChoiceSetting(
    icon: ImageVector,
    label: String,
    selectedId: String,
    entries: Array<String>,
    ids: Array<String>,
    onSelectedId: (String) -> Unit
) {
    val selectedIndex = ids.indexOf(selectedId).takeIf { it >= 0 } ?: 0
    ChoiceSetting(
        icon = icon,
        label = label,
        selected = entries.getOrElse(selectedIndex) { selectedId },
        entries = entries
    ) { selectedLabel ->
        val index = entries.indexOf(selectedLabel).coerceAtLeast(0)
        onSelectedId(ids.getOrElse(index) { selectedId })
    }
}

@Composable
private fun ReadOnlySetting(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingIcon(icon)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f)),
        tonalElevation = 0.dp
    ) {
        Column { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceSetting(
    icon: ImageVector,
    label: String,
    selected: String,
    entries: Array<String>,
    showIcon: Boolean = true,
    installedEntries: Array<String> = entries,
    onInstall: ((String) -> Unit)? = null,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showIcon) {
                    SettingIcon(icon)
                    Spacer(Modifier.width(13.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        selected,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (expanded) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 20.dp)
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
                Text(
                    uiStr(R.string.ui_choose_an_option),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 10.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(entries.toList(), key = { it }) { value ->
                        val installed = installedEntries.any { it.equals(value, ignoreCase = true) }
                                || value.equals(selected, ignoreCase = true)
                        Surface(
                            onClick = {
                                expanded = false
                                if (installed || onInstall == null) onSelected(value)
                                else onInstall(value)
                            },
                            modifier = Modifier.fillMaxWidth().alpha(if (installed) 1f else 0.48f),
                            shape = RoundedCornerShape(10.dp),
                            color = if (value == selected)
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
                            else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                if (value == selected) {
                                    Icon(Icons.Outlined.Check, null)
                                } else if (!installed) {
                                    Text(
                                        uiStr(R.string.ui_download_2),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionSetting(
    icon: ImageVector,
    title: String,
    subtitle: String,
    expanded: Boolean = false,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingIcon(icon)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RendererOptionsPanel(
    nativeRenderer: Boolean,
    presentMode: String,
    onPresentMode: (String) -> Unit,
    driverEntries: Array<String>,
    driverIds: Array<String>,
    driverId: String,
    onDriverId: (String) -> Unit,
    filterMode: Int,
    onFilterMode: (Int) -> Unit,
    swapRB: Boolean,
    onSwapRB: (Boolean) -> Unit
) {
    val presentEntries = arrayOf("Mailbox", "Fifo")
    val presentIds = arrayOf("mailbox", "fifo")
    val filterEntries = if (nativeRenderer) {
        arrayOf("Bilinear", "Nearest neighbor")
    } else {
        arrayOf(
            "Bilinear",
            "Nearest neighbor",
            "Snapdragon Super Resolution",
            "AMD FidelityFX Super Resolution"
        )
    }
    Surface(
        modifier = Modifier.padding(start = 65.dp, end = 12.dp, bottom = 10.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            if (!nativeRenderer) {
                InlineChoice(
                    label = uiStr(R.string.ui_present_mode),
                    selected = presentEntries[presentIds.indexOf(presentMode).coerceAtLeast(0)],
                    entries = presentEntries
                ) { value -> onPresentMode(presentIds[presentEntries.indexOf(value)]) }
                ThinDivider()
                val selectedDriver = driverIds.indexOf(driverId).takeIf { it >= 0 } ?: 0
                InlineChoice(
                    label = uiStr(R.string.ui_renderer_driver),
                    selected = driverEntries.getOrElse(selectedDriver) { "System" },
                    entries = driverEntries
                ) { value ->
                    val index = driverEntries.indexOf(value).coerceAtLeast(0)
                    onDriverId(driverIds.getOrElse(index) { "system" })
                }
                ThinDivider()
            }
            InlineChoice(
                label = uiStr(R.string.ui_texture_filter),
                selected = filterEntries.getOrElse(filterMode) { filterEntries[0] },
                entries = filterEntries
            ) { value -> onFilterMode(filterEntries.indexOf(value).coerceAtLeast(0)) }
            ThinDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(uiStr(R.string.ui_swap_red_blue_channels), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = swapRB, onCheckedChange = onSwapRB)
            }
        }
    }
}

@Composable
private fun GraphicsDriverOptionsPanel(
    version: String,
    onVersion: (String) -> Unit,
    versionEntries: Array<String>,
    installedVersionEntries: Array<String>,
    onInstallVersion: (String) -> Unit,
    vulkanVersion: String,
    onVulkanVersion: (String) -> Unit,
    presentMode: String,
    onPresentMode: (String) -> Unit,
    syncFrame: Boolean,
    onSyncFrame: (Boolean) -> Unit,
    disablePresentWait: Boolean,
    onDisablePresentWait: (Boolean) -> Unit,
    resourceType: String,
    onResourceType: (String) -> Unit,
    bcnEmulation: String,
    onBcnEmulation: (String) -> Unit,
    bcnEmulationType: String,
    onBcnEmulationType: (String) -> Unit,
    bcnEmulationCache: Boolean,
    onBcnEmulationCache: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.padding(start = 65.dp, end = 12.dp, bottom = 10.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            ChoiceSetting(
                icon = Icons.Outlined.DesktopWindows,
                label = uiStr(R.string.ui_driver_version),
                selected = version,
                entries = versionEntries,
                showIcon = false,
                installedEntries = installedVersionEntries,
                onInstall = onInstallVersion,
                onSelected = onVersion
            )
            ThinDivider()
            InlineChoice(uiStr(R.string.graphics_driver_vulkan_version), vulkanVersion, arrayOf("1.1", "1.2", "1.3"), onVulkanVersion)
            ThinDivider()
            InlineChoice(uiStr(R.string.ui_present_mode), presentMode, arrayOf("mailbox", "fifo", "immediate", "relaxed"), onPresentMode)
            ThinDivider()
            InlineChoice(uiStr(R.string.ui_resource_type), resourceType, arrayOf("auto", "buffer", "image"), onResourceType)
            ThinDivider()
            InlineChoice(uiStr(R.string.ui_bcn_emulation), bcnEmulation, arrayOf("none", "partial", "full", "auto"), onBcnEmulation)
            ThinDivider()
            InlineChoice(uiStr(R.string.ui_bcn_emulation_type), bcnEmulationType, arrayOf("software", "compute"), onBcnEmulationType)
            ThinDivider()
            ToggleSetting(uiStr(R.string.ui_bcn_emulation_cache), bcnEmulationCache, onBcnEmulationCache)
            ThinDivider()
            ToggleSetting(uiStr(R.string.ui_sync_frame), syncFrame, onSyncFrame)
            ThinDivider()
            ToggleSetting(uiStr(R.string.ui_disable_present_wait), disablePresentWait, onDisablePresentWait)
        }
    }
}

@Composable
private fun WrapperOptionsPanel(
    dxvk: Boolean,
    dxvkVersion: String,
    onDxvkVersion: (String) -> Unit,
    dxvkEntries: Array<String>,
    installedDxvkEntries: Array<String>,
    vkd3dVersion: String,
    onVkd3dVersion: (String) -> Unit,
    vkd3dEntries: Array<String>,
    installedVkd3dEntries: Array<String>,
    vkd3dLevel: String,
    onVkd3dLevel: (String) -> Unit,
    frameRate: String,
    onFrameRate: (String) -> Unit,
    maxFrameLatency: Boolean,
    onMaxFrameLatency: (Boolean) -> Unit,
    async: Boolean,
    onAsync: (Boolean) -> Unit,
    asyncCache: Boolean,
    onAsyncCache: (Boolean) -> Unit,
    ddrawWrapper: String,
    onDdrawWrapper: (String) -> Unit,
    csmt: Boolean,
    onCsmt: (Boolean) -> Unit,
    strictShaderMath: Boolean,
    onStrictShaderMath: (Boolean) -> Unit,
    offscreenMode: String,
    onOffscreenMode: (String) -> Unit,
    wineRenderer: String,
    onWineRenderer: (String) -> Unit,
    videoMemory: String,
    onVideoMemory: (String) -> Unit,
    onManageComponents: () -> Unit,
    onInstallComponent: (String, String) -> Unit
) {
    Surface(
        modifier = Modifier.padding(start = 65.dp, end = 12.dp, bottom = 10.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (dxvk) {
                ChoiceSetting(
                    icon = Icons.Outlined.Memory,
                    label = uiStr(R.string.dxvk_version),
                    selected = dxvkVersion.ifBlank { dxvkEntries.firstOrNull().orEmpty() },
                    entries = dxvkEntries,
                    showIcon = false,
                    installedEntries = installedDxvkEntries,
                    onInstall = { onInstallComponent("DXVK", it) },
                    onSelected = onDxvkVersion
                )
                ChoiceSetting(
                    icon = Icons.Outlined.Memory,
                    label = uiStr(R.string.vkd3d_version),
                    selected = vkd3dVersion.ifBlank { vkd3dEntries.firstOrNull().orEmpty() },
                    entries = vkd3dEntries,
                    showIcon = false,
                    installedEntries = installedVkd3dEntries,
                    onInstall = { if (!it.equals("None", true)) onInstallComponent("VKD3D", it) },
                    onSelected = onVkd3dVersion
                )
                InlineChoice(
                    label = uiStr(R.string.vkd3d_feature_level),
                    selected = vkd3dLevel,
                    entries = arrayOf("12_0", "12_1", "12_2", "11_1", "11_0", "10_1", "10_0", "9_3", "9_2", "9_1"),
                    onSelected = onVkd3dLevel
                )
                CompactTextField(
                    label = uiStr(R.string.ui_frame_rate_limit),
                    value = frameRate,
                    onValueChange = onFrameRate,
                    numeric = true
                )
                ToggleSetting(uiStr(R.string.ui_max_frame_latency), maxFrameLatency, onMaxFrameLatency)
                ToggleSetting(uiStr(R.string.ui_async_shaders), async, onAsync)
                ToggleSetting(uiStr(R.string.ui_async_shader_cache), asyncCache, onAsyncCache)
                InlineChoice(
                    label = uiStr(R.string.ddraw_wrapper),
                    selected = ddrawWrapper,
                    entries = arrayOf("wined3d", "cnc-ddraw"),
                    onSelected = onDdrawWrapper
                )
            } else {
                ToggleSetting(uiStr(R.string.ui_csmt), csmt, onCsmt)
                ToggleSetting(uiStr(R.string.strict_shader_math), strictShaderMath, onStrictShaderMath)
                InlineChoice(
                    label = uiStr(R.string.ui_offscreen_rendering),
                    selected = offscreenMode,
                    entries = arrayOf("fbo", "backbuffer"),
                    onSelected = onOffscreenMode
                )
                InlineChoice(
                    label = uiStr(R.string.ui_wined3d_renderer),
                    selected = wineRenderer,
                    entries = arrayOf("gl", "vulkan", "gdi"),
                    onSelected = onWineRenderer
                )
                CompactTextField(
                    label = uiStr(R.string.ui_video_memory_mb),
                    value = videoMemory,
                    onValueChange = onVideoMemory,
                    numeric = true
                )
            }
            ThinDivider()
            Surface(
                onClick = onManageComponents,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Memory, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(uiStr(R.string.ui_manage_installed_versions), style = MaterialTheme.typography.titleSmall)
                        Text(
                            uiStr(R.string.ui_install_or_remove_dxvk_vkd3d_and_emulator_components),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
        }
    }
}

@Composable
private fun CompactTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Ascii
        ),
        shape = RoundedCornerShape(10.dp)
    )
}

@Composable
private fun ToggleSetting(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CustomResolutionFields(
    width: String,
    height: String,
    onWidth: (String) -> Unit,
    onHeight: (String) -> Unit
) {
    Row(
        modifier = Modifier.padding(start = 65.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = width,
            onValueChange = onWidth,
            modifier = Modifier.weight(1f),
            label = { Text(uiStr(R.string.width)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(10.dp)
        )
        OutlinedTextField(
            value = height,
            onValueChange = onHeight,
            modifier = Modifier.weight(1f),
            label = { Text(uiStr(R.string.height)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(10.dp)
        )
    }
}

@Composable
private fun InlineChoice(label: String, selected: String, entries: Array<String>, onSelected: (String) -> Unit) {
    ChoiceSetting(
        icon = Icons.Outlined.Tune,
        label = label,
        selected = selected,
        entries = entries,
        onSelected = onSelected,
        showIcon = false
    )
}

@Composable
private fun ThinDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
    )
}

@Composable
private fun SettingIcon(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 65.dp, end = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
    )
}

@Composable
private fun InfoText(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun parseConfig(data: String): LinkedHashMap<String, String> {
    val result = linkedMapOf<String, String>()
    data.split(',').forEach { entry ->
        val separator = entry.indexOf('=')
        if (separator > 0) {
            result[entry.substring(0, separator)] = entry.substring(separator + 1)
        }
    }
    return result
}

private fun mergeConfig(data: String, updates: Map<String, String>): String {
    val values = parseConfig(data)
    updates.forEach { (key, value) -> values[key] = value }
    return values.entries.joinToString(",") { (key, value) -> "$key=$value" }
}

private fun parseSemicolonConfig(data: String): LinkedHashMap<String, String> {
    val result = linkedMapOf<String, String>()
    data.split(';').forEach { entry ->
        val separator = entry.indexOf('=')
        if (separator > 0) result[entry.substring(0, separator)] = entry.substring(separator + 1)
    }
    return result
}

private fun mergeSemicolonConfig(data: String, updates: Map<String, String>): String {
    val values = parseSemicolonConfig(data)
    updates.forEach { (key, value) -> values[key] = value }
    return values.entries.joinToString(";") { (key, value) -> "$key=$value" }
}
