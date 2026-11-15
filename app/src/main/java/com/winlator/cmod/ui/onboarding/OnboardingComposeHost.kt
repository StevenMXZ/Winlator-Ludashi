package com.winlator.cmod.ui.onboarding

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.winlator.cmod.box64.Box64Preset
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.GPUInformation
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.fexcore.FEXCorePreset
import com.winlator.cmod.ui.applyAppFullscreen
import com.winlator.cmod.ui.settings.cleanContainerEnvironment
import com.winlator.cmod.ui.theme.WinZTheme
import org.json.JSONObject

@Immutable
data class OnboardingComponent @JvmOverloads constructor(
    val id: String,
    val type: String,
    val name: String,
    val installed: Boolean,
    val recommended: Boolean,
    val removable: Boolean,
    val runtimeIdentifier: String? = null,
    val inUse: Boolean = false,
    val bundled: Boolean = false
)

interface OnboardingCallbacks {
    fun onInstall(componentId: String)
    fun onRemove(componentId: String)
    fun onInstallBundledRuntime()
    fun onRemoveBundledRuntime()
    fun onBrowseLocal()
    fun onBrowseDriver()
    fun onBrowseWrapper()
    fun onRuntimeSelected(runtimeIdentifier: String)
    fun onRequestPermissions()
    fun onRetryCore()
    fun onCloseComponents()
}

class OnboardingComposeController internal constructor(
    private val coreReady: MutableState<Boolean>,
    private val coreProgress: MutableState<Int>,
    private val bundledWineInstalled: MutableState<Boolean>,
    private val bundledWineInUse: MutableState<Boolean>,
    private val components: MutableState<List<OnboardingComponent>>,
    private val installingId: MutableState<String?>,
    private val installingLabel: MutableState<String?>,
    private val installingProgress: MutableState<Int>,
    private val initialContainerPreparing: MutableState<Boolean>,
    private val initialContainerReady: MutableState<Boolean>
) {
    fun updateCore(ready: Boolean, progress: Int) {
        coreReady.value = ready
        coreProgress.value = progress.coerceIn(0, 100)
    }

    fun updateBundledRuntime(installed: Boolean, inUse: Boolean) {
        bundledWineInstalled.value = installed
        bundledWineInUse.value = inUse
    }

    fun setComponents(value: List<OnboardingComponent>) {
        components.value = value.toList()
    }

    fun setInstallBusy(componentId: String?, busy: Boolean) {
        installingId.value = if (busy) componentId else null
        if (busy) {
            installingLabel.value = null
            installingProgress.value = -1
        } else {
            installingLabel.value = null
            installingProgress.value = -1
        }
    }

    fun updateInstallProgress(label: String?, progress: Int) {
        installingLabel.value = label
        installingProgress.value = progress.coerceIn(-1, 100)
    }

    fun updateInitialContainer(preparing: Boolean, ready: Boolean) {
        initialContainerPreparing.value = preparing
        initialContainerReady.value = ready
    }
}

object OnboardingComposeHost {
    @JvmStatic
    fun attach(
        activity: ComponentActivity,
        initialCoreReady: Boolean,
        initialCoreProgress: Int,
        initialBundledWineInstalled: Boolean,
        initialBundledWineInUse: Boolean,
        componentManagerMode: Boolean,
        callbacks: OnboardingCallbacks
    ): OnboardingComposeController {
        val ready = mutableStateOf(initialCoreReady)
        val progress = mutableStateOf(initialCoreProgress)
        val bundledInstalled = mutableStateOf(initialBundledWineInstalled)
        val bundledInUse = mutableStateOf(initialBundledWineInUse)
        val components = mutableStateOf<List<OnboardingComponent>>(emptyList())
        val installing = mutableStateOf<String?>(null)
        val installLabel = mutableStateOf<String?>(null)
        val installProgress = mutableStateOf(-1)
        val containerPreparing = mutableStateOf(false)
        val containerReady = mutableStateOf(false)
        val controller = OnboardingComposeController(
            ready,
            progress,
            bundledInstalled,
            bundledInUse,
            components,
            installing,
            installLabel,
            installProgress,
            containerPreparing,
            containerReady
        )

        applyAppFullscreen(activity)
        activity.setContent {
            WinZTheme {
                OnboardingFlow(
                    activity,
                    ready,
                    progress,
                    bundledInstalled,
                    bundledInUse,
                    components,
                    installing,
                    installLabel,
                    installProgress,
                    containerPreparing,
                    containerReady,
                    componentManagerMode,
                    callbacks
                )
            }
        }
        return controller
    }
}

private enum class OnboardingPage { Welcome, Theme, Components, Runtime, Access }

private fun prepareInitialContainer(
    activity: ComponentActivity,
    runtimeIdentifier: String,
    preparing: MutableState<Boolean>,
    ready: MutableState<Boolean>
) {
    if (preparing.value || ready.value || runtimeIdentifier.isBlank()) return

    val manager = ContainerManager(activity)
    if (manager.containers.isNotEmpty()) {
        ready.value = true
        return
    }

    val contents = ContentsManager(activity).apply { syncContents() }
    val wineInfo = WineInfo.fromIdentifier(activity, contents, runtimeIdentifier)
    if (wineInfo.path.isNullOrBlank()) {
        Toast.makeText(activity, uiStr(R.string.ui_the_selected_wine_proton_layer_is_no_longer_installed), Toast.LENGTH_LONG).show()
        return
    }

    preparing.value = true
    ready.value = false
    try {
        val defaultDriver = if (GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, activity)) {
            DefaultVersion.WRAPPER_ADRENO
        } else {
            DefaultVersion.WRAPPER
        }
        val graphicsConfig = Container.DEFAULT_GRAPHICSDRIVERCONFIG.replace(
            ";version=;",
            ";version=$defaultDriver;"
        )
        val data = JSONObject().apply {
            put("name", "Container-${manager.nextContainerId}")
            put("screenSize", Container.DEFAULT_SCREEN_SIZE)
            put("envVars", cleanContainerEnvironment(Container.DEFAULT_ENV_VARS))
            put("graphicsDriver", Container.DEFAULT_GRAPHICS_DRIVER)
            put("graphicsDriverConfig", graphicsConfig)
            put("rendererNative", false)
            put("rendererPresentMode", "fifo")
            put("dxwrapper", Container.DEFAULT_DXWRAPPER)
            put("dxwrapperConfig", Container.DEFAULT_DXWRAPPERCONFIG)
            put("audioDriver", Container.DEFAULT_AUDIO_DRIVER)
            put("emulator", if (wineInfo.isArm64EC) "FEXCore" else "Box64")
            put("wincomponents", Container.DEFAULT_WINCOMPONENTS)
            put("drives", Container.DEFAULT_DRIVES)
            put("box64Version", if (wineInfo.isArm64EC) DefaultVersion.WOWBOX64 else DefaultVersion.BOX64)
            put("box64Preset", Box64Preset.COMPATIBILITY)
            put("fexcoreVersion", DefaultVersion.FEXCORE)
            put("fexcorePreset", FEXCorePreset.INTERMEDIATE)
            put("wineVersion", runtimeIdentifier)
        }

        manager.createContainerAsync(data, contents) { created ->
            preparing.value = false
            if (created == null) {
                ready.value = false
                Toast.makeText(activity, uiStr(R.string.ui_unable_to_create_the_first_container), Toast.LENGTH_LONG).show()
            } else {
                ready.value = true
            }
        }
    } catch (_: Exception) {
        preparing.value = false
        ready.value = false
        Toast.makeText(activity, uiStr(R.string.ui_unable_to_prepare_the_first_container), Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun OnboardingFlow(
    activity: ComponentActivity,
    ready: State<Boolean>,
    progress: State<Int>,
    bundledInstalled: State<Boolean>,
    bundledInUse: State<Boolean>,
    components: State<List<OnboardingComponent>>,
    installing: State<String?>,
    installingLabel: State<String?>,
    installingProgress: State<Int>,
    containerPreparing: MutableState<Boolean>,
    containerReady: MutableState<Boolean>,
    managerMode: Boolean,
    cb: OnboardingCallbacks
) {
    var page by rememberSaveable(managerMode) {
        mutableStateOf(if (managerMode) OnboardingPage.Components else OnboardingPage.Theme)
    }

    val hasInstalledRuntime = bundledInstalled.value || components.value.any {
        it.installed && (it.type == "Wine" || it.type == "Proton") && !it.runtimeIdentifier.isNullOrBlank()
    }

    LaunchedEffect(containerReady.value, page) {
        if (!managerMode && page == OnboardingPage.Runtime && containerReady.value) {
            page = OnboardingPage.Access
        }
    }

    when (page) {
        OnboardingPage.Welcome -> ClassicWinlatorWelcome(
            ready,
            progress,
            start = { page = OnboardingPage.Theme },
            skip = { page = OnboardingPage.Runtime },
            retry = { cb.onRetryCore() }
        )

        OnboardingPage.Theme -> OnboardingThemeScreen(
            onContinue = { page = OnboardingPage.Components }
        )

        OnboardingPage.Components -> OnboardingComponentsScreen(
            ready = ready,
            progress = progress,
            bundledInstalled = bundledInstalled,
            bundledInUse = bundledInUse,
            all = components.value,
            installing = installing.value,
            installingLabel = installingLabel.value,
            installingProgress = installingProgress.value,
            managerMode = managerMode,
            onBack = {
                if (managerMode) cb.onCloseComponents()
                else page = OnboardingPage.Theme
            },
            onContinue = {
                if (managerMode) cb.onCloseComponents()
                else if (ready.value && hasInstalledRuntime) page = OnboardingPage.Runtime
            },
            cb = cb
        )

        OnboardingPage.Runtime -> OnboardingRuntimeSelectionScreen(
            components = components.value,
            bundledInstalled = bundledInstalled.value,
            preparing = containerPreparing.value,
            onBack = { page = OnboardingPage.Components },
            onContinue = { runtime ->
                if (ready.value && hasInstalledRuntime && !containerPreparing.value) {
                    cb.onRuntimeSelected(runtime)
                    prepareInitialContainer(activity, runtime, containerPreparing, containerReady)
                }
            }
        )

        OnboardingPage.Access -> OnboardingAccessScreen(
            back = { page = OnboardingPage.Runtime },
            next = { cb.onRequestPermissions() }
        )
    }
}
