package com.winlator.cmod.ui.onboarding

import com.winlator.cmod.ui.uiOptionLabel
import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.LineHeightStyle
import com.winlator.cmod.core.ProtonPackageManager
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.destructiveColor
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor

private val bundledRuntimeId = "bundled:${ProtonPackageManager.DEFAULT_IDENTIFIER}"
private val bundledRuntimeName = ProtonPackageManager.getPackage(ProtonPackageManager.DEFAULT_IDENTIFIER)?.title
    ?: ProtonPackageManager.DEFAULT_IDENTIFIER

private val componentCategories = listOf(
    "Recommended", "Wine & Proton", "DXVK", "VKD3D", "FEXCore", "Box64", "WOWBox64", "AdrenoTools"
)

private val latestRecommendedTypes = setOf("DXVK", "VKD3D", "FEXCore", "Box64", "WOWBox64")

private fun compareVersionParts(left: List<Int>, right: List<Int>): Int {
    val count = maxOf(left.size, right.size)
    for (index in 0 until count) {
        val a = left.getOrElse(index) { 0 }
        val b = right.getOrElse(index) { 0 }
        if (a != b) return a.compareTo(b)
    }
    return 0
}

private fun componentVersionParts(type: String, name: String): List<Int> {
    var clean = name.lowercase()
        .replace("arm64ec", "")
        .replace("x86_64", "")

    if (type == "FEXCore") {
        val token = Regex("\\d{6}|\\d{4}(?:\\.\\d+)?").find(clean)?.value.orEmpty()
        val base = token.substringBefore('.')
        val suffix = token.substringAfter('.', "").toIntOrNull()
        return when (base.length) {
            6 -> listOf(
                base.substring(0, 2).toIntOrNull() ?: 0,
                base.substring(2, 4).toIntOrNull() ?: 0,
                base.substring(4, 6).toIntOrNull() ?: 0
            )
            4 -> buildList {
                add(base.substring(0, 2).toIntOrNull() ?: 0)
                add(base.substring(2, 4).toIntOrNull() ?: 0)
                if (suffix != null) add(suffix)
            }
            else -> emptyList()
        }
    }

    if ((type == "Box64" || type == "WOWBox64") && Regex("^0?\\d{3}(?:\\D|$)").containsMatchIn(clean)) {
        val digits = Regex("\\d{3}").find(clean)?.value.orEmpty()
        if (digits.length == 3) {
            return listOf(
                digits.substring(0, 1).toIntOrNull() ?: 0,
                digits.substring(1, 2).toIntOrNull() ?: 0,
                digits.substring(2, 3).toIntOrNull() ?: 0
            )
        }
    }

    if (type == "DXVK" && clean.startsWith("11.1")) {
        clean = "1.1.1" + clean.removePrefix("11.1")
    }

    val token = Regex("\\d+(?:\\.\\d+){0,3}").find(clean)?.value ?: return emptyList()
    return token.split('.').map { it.toIntOrNull() ?: 0 }
}

internal fun recommendedComponentIds(all: List<OnboardingComponent>): Set<String> {
    val result = all.filter { it.recommended && it.type !in latestRecommendedTypes && it.type != "Wine" && it.type != "Proton" }
        .mapTo(linkedSetOf()) { it.id }

    val runtimeType = if (all.any { it.type == "Proton" }) "Proton" else "Wine"
    (latestRecommendedTypes + runtimeType).forEach { type ->
        all.withIndex()
            .filter { it.value.type == type }
            .maxWithOrNull { left, right ->
                val byVersion = compareVersionParts(
                    componentVersionParts(type, left.value.name),
                    componentVersionParts(type, right.value.name)
                )
                if (byVersion != 0) byVersion else left.index.compareTo(right.index)
            }
            ?.value
            ?.let { result.add(it.id) }
    }
    return result
}

@Composable
internal fun OnboardingComponentsScreen(
    ready: State<Boolean>,
    progress: State<Int>,
    bundledInstalled: State<Boolean>,
    bundledInUse: State<Boolean>,
    all: List<OnboardingComponent>,
    installing: String?,
    installingLabel: String?,
    installingProgress: Int,
    managerMode: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    cb: OnboardingCallbacks
) {
    var category by rememberSaveable { mutableStateOf("Recommended") }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val recommendedIds = remember(all) { recommendedComponentIds(all) }
    val visible = remember(all, category, recommendedIds) {
        all.filter {
            when (category) {
                "Recommended" -> it.id in recommendedIds
                "Wine & Proton" -> it.type == "Wine" || it.type == "Proton"
                else -> it.type == category
            }
        // Installed rows first. The catalog order is by type and version code, so an installed
        // row with a low version code (a local package, an older build) or of the second type
        // in "Wine & Proton" used to sink below the uninstalled ones. The sort is stable, so the
        // order inside each group stays as the catalog gave it.
        }.sortedByDescending { it.installed }
    }
    val hasInstalledRuntime = bundledInstalled.value || all.any {
        it.installed && (it.type == "Wine" || it.type == "Proton") && !it.runtimeIdentifier.isNullOrBlank()
    }
    val showBundled = category == "Recommended" || category == "Wine & Proton"
    val showLocalInstallProgress = installing == "local" || installing == "driver-local"

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (landscape) {
            // Landscape: height is the scarce axis, so the list owns the whole width and everything
            // else is packed above it — title/subtitle and the source card share one header row,
            // category chips get a full-width row (all 8 visible on most phones), and the bundled
            // runtime and local-install progress ride at the top of the list
            // instead of a fixed side column (which used to clip at the bottom on phones).
            LandscapeComponentsHeader(
                managerMode = managerMode,
                onBack = onBack,
                title = if (managerMode) uiStr(R.string.ui_components) else uiStr(R.string.ui_choose_components),
                subtitle = if (managerMode) uiStr(R.string.ui_install_and_manage_runtime_versions)
                else uiStr(R.string.ui_install_a_wine_or_proton_layer_before_con),
                onBrowseLocal = { cb.onBrowseLocal() },
                onOpenServers = { Unit }
            )
            // 12dp under the 64dp header, like the Containers list: first content row at 76dp.
            Spacer(Modifier.height(12.dp))
            CategorySelector(category, contentPadding = PaddingValues(horizontal = 22.dp)) { category = it }
            Spacer(Modifier.height(10.dp))
            ComponentList(
                visible,
                all.isEmpty(),
                installing,
                installingLabel,
                installingProgress,
                cb,
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 12.dp)
            ) {
                if (showBundled) item(key = "bundled-runtime") {
                    CoreComponentCard(
                        ready = ready,
                        progress = progress,
                        installed = bundledInstalled.value,
                        inUse = bundledInUse.value,
                        busy = installing == bundledRuntimeId,
                        locked = installing != null,
                        onInstall = cb::onInstallBundledRuntime,
                        onRemove = cb::onRemoveBundledRuntime
                    )
                }
                if (!managerMode && !hasInstalledRuntime) item(key = "runtime-hint") {
                    // Right under the bundled runtime: it's the reason Continue is disabled.
                    Text(
                        if (!ready.value) uiStr(R.string.ui_wait_for_to_finish_installing_or_install, bundledRuntimeName)
                        else uiStr(R.string.ui_install_at_least_one_wine_or_proton_versi),
                        Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showLocalInstallProgress) item(key = "local-progress") {
                    InstallProgressCard(installingLabel, installingProgress)
                }
            }
        } else {
            // Opened from Settings (manager mode): a top bar with a back arrow, like Containers,
            // instead of the first-run wizard's Back/Done footer.
            if (managerMode) ManagerTopBar(onBack)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                // Manager mode: 12dp under the 64dp top bar, same as the Containers list, so the
                // subtitle starts at the height of the first container card (76dp).
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = if (managerMode) 12.dp else 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    if (!managerMode) {
                        Text(uiStr(R.string.ui_choose_components), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        if (managerMode) uiStr(R.string.ui_install_and_manage_runtime_versions)
                        else uiStr(R.string.ui_install_as_many_versions_as_you_want_at_l),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = LocalTextStyle.current.copy(
                            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.FirstLineTop)
                        )
                    )
                    Spacer(Modifier.height(16.dp))
                    SourceSelector(servers = { Unit }, local = { cb.onBrowseLocal() })
                    if (showLocalInstallProgress) {
                        Spacer(Modifier.height(10.dp))
                        InstallProgressCard(installingLabel, installingProgress)
                    }
                    Spacer(Modifier.height(12.dp))
                    CategorySelector(category) { category = it }
                    if (showBundled) {
                        Spacer(Modifier.height(10.dp))
                        CoreComponentCard(
                            ready = ready,
                            progress = progress,
                            installed = bundledInstalled.value,
                            inUse = bundledInUse.value,
                            busy = installing == bundledRuntimeId,
                            locked = installing != null,
                            onInstall = cb::onInstallBundledRuntime,
                            onRemove = cb::onRemoveBundledRuntime
                        )
                    }
                }
                if (all.isEmpty()) item { LoadingCard() }
                else items(visible, key = { it.id }) {
                    ComponentCard(
                        it,
                        installing == it.id,
                        installing != null,
                        installingLabel,
                        installingProgress,
                        cb
                    )
                }
                if (!managerMode && !hasInstalledRuntime) {
                    item {
                        Text(
                            if (!ready.value) uiStr(R.string.ui_continue_unlocks_when_finishes_installing, bundledRuntimeName)
                            else uiStr(R.string.ui_install_at_least_one_wine_or_proton_versi),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        // The Back/Continue footer only belongs to the first-run flow.
        if (!managerMode) {
            ComponentsFooter(
                back = onBack,
                next = onContinue,
                landscape = landscape,
                nextEnabled = hasInstalledRuntime,
                nextLabel = uiStr(R.string.ui_continue)
            )
        }
    }
}

@Composable
private fun ComponentList(
    list: List<OnboardingComponent>,
    loading: Boolean,
    installing: String?,
    installingLabel: String?,
    installingProgress: Int,
    cb: OnboardingCallbacks,
    modifier: Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 8.dp),
    header: LazyListScope.() -> Unit = {}
) {
    LazyColumn(modifier, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        header()
        if (loading) item { LoadingCard() }
        else if (list.isEmpty()) item {
            Surface(Modifier.fillMaxWidth(), shape = WinZShapes.Medium, color = MaterialTheme.colorScheme.surface) {
                Text(
                    uiStr(R.string.ui_no_components_available_in_this_category),
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        else items(list, key = { it.id }) {
            ComponentCard(
                it,
                installing == it.id,
                installing != null,
                installingLabel,
                installingProgress,
                cb
            )
        }
    }
}

// compact = the landscape header variant: wraps its content (no stretched halves) at 44dp.
@Composable
private fun SourceSelector(compact: Boolean = false, servers: () -> Unit, local: () -> Unit) {
    Surface(
        if (compact) Modifier else Modifier.fillMaxWidth(),
        shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Row(Modifier.height(if (compact) 44.dp else 56.dp)) {
            val part = if (compact) Modifier else Modifier.weight(1f)
            SourcePart(Icons.Outlined.Dns, uiStr(R.string.ui_winlator_servers), true, servers, part)
            SourcePart(Icons.Outlined.Folder, uiStr(R.string.ui_local_package), false, local, part, accent = true)
        }
    }
}

@Composable
private fun SourcePart(icon: ImageVector, label: String, selected: Boolean, click: () -> Unit, modifier: Modifier, accent: Boolean = false) {
    val accentColor = controlAccentColor()
    Surface(
        onClick = click,
        enabled = !selected,
        modifier = modifier.fillMaxHeight(),
        color = if (accent) accentColor else if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        contentColor = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            // softWrap = false: with maxLines = 1 alone a two-word label that is a bit too wide
            // wraps at the space and the second line is clipped ("Winlator servers" -> "Winlator").
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

// Landscape header: [back] Title / subtitle ............ [Winlator servers | Local package]
// Manager mode gets the back arrow (same slot as ManagerTopBar); first-run onboarding has its
// own Back in the footer, so it just starts at the content inset.
@Composable
private fun LandscapeComponentsHeader(
    managerMode: Boolean,
    onBack: () -> Unit,
    title: String,
    subtitle: String,
    onBrowseLocal: () -> Unit,
    onOpenServers: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = if (managerMode) 4.dp else 22.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (managerMode) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = uiStr(R.string.ui_back))
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (managerMode) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = if (managerMode) null else FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        SourceSelector(compact = true, servers = onOpenServers, local = onBrowseLocal)
    }
}


@Composable
private fun CategorySelector(
    selected: String,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    select: (String) -> Unit
) {
    // Padding goes inside the scroll so chips scroll out to the screen edge instead of being
    // clipped at the padded boundary.
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        componentCategories.forEach {
            Surface(
                onClick = { select(it) },
                shape = WinZShapes.Small,
                color = if (selected == it) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                contentColor = if (selected == it) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                border = BorderStroke(1.dp, hairlineColor())
            ) {
                Text(uiOptionLabel(it), Modifier.padding(horizontal = 13.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CoreComponentCard(
    ready: State<Boolean>,
    progress: State<Int>,
    installed: Boolean,
    inUse: Boolean,
    busy: Boolean,
    locked: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                ScrollableName(bundledRuntimeName)
                val status = when {
                    busy -> uiStr(R.string.ui_working)
                    installed && inUse -> uiStr(R.string.ui_bundled_installed_in_use)
                    installed -> uiStr(R.string.ui_bundled_installed)
                    !ready.value -> uiStr(R.string.ui_bundled_installing, progress.value)
                    else -> uiStr(R.string.ui_bundled_not_installed)
                }
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            when {
                busy || (!ready.value && !installed) -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                installed -> Button(
                    onClick = onRemove,
                    enabled = !locked && !inUse,
                    colors = ButtonDefaults.buttonColors(containerColor = destructiveColor(), contentColor = MaterialTheme.colorScheme.onError)
                ) {
                    Text(if (inUse) uiStr(R.string.ui_in_use) else uiStr(R.string.ui_delete))
                }
                else -> Button(
                    onClick = onInstall,
                    enabled = !locked,
                    colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = MaterialTheme.colorScheme.onPrimary)
                ) {
                    Icon(Icons.Outlined.Download, null)
                    Spacer(Modifier.width(5.dp))
                    Text(uiStr(R.string.install))
                }
            }
        }
    }
}

// Single-line component name that can be swiped sideways when it doesn't fit (phones in
// portrait, long driver names). Instead of an ellipsis — which hid the distinguishing tail of
// version strings like "-arm64ec-2" — the clipped side fades out, so it's visible that there's
// more text and in which direction. Nothing fades when the name fits.
@Composable
private fun ScrollableName(text: String, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Text(
        text,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = NAME_FADE_WIDTH.toPx().coerceAtMost(size.width / 3f)
                if (scroll.value > 0) {
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = 0f, endX = fade),
                        blendMode = BlendMode.DstOut
                    )
                }
                if (scroll.value < scroll.maxValue) {
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = size.width - fade, endX = size.width),
                        blendMode = BlendMode.DstOut
                    )
                }
            }
            .horizontalScroll(scroll),
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false
    )
}

private val NAME_FADE_WIDTH = 24.dp

@Composable
private fun ComponentCard(
    item: OnboardingComponent,
    busy: Boolean,
    locked: Boolean,
    installingLabel: String?,
    installingProgress: Int,
    cb: OnboardingCallbacks
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    ScrollableName(item.name)
                    val status = when {
                        busy && installingProgress >= 0 ->
                            "${installingLabel ?: "Installing"} • ${installingProgress}%"
                        busy -> installingLabel ?: uiStr(R.string.ui_working)
                        item.inUse -> uiStr(R.string.ui_in_use_2, item.type)
                        else -> item.type
                    }
                    Text(
                        status,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                else if (item.installed && item.removable) {
                    Button(
                        onClick = { cb.onRemove(item.id) },
                        enabled = !locked && !item.inUse,
                        colors = ButtonDefaults.buttonColors(containerColor = destructiveColor(), contentColor = MaterialTheme.colorScheme.onError)
                    ) {
                        Text(if (item.inUse) uiStr(R.string.ui_in_use) else uiStr(R.string.ui_delete))
                    }
                } else if (!item.installed) {
                    FilledTonalIconButton(
                        onClick = { cb.onInstall(item.id) },
                        enabled = !locked,
                        modifier = Modifier.padding(start = 8.dp).size(40.dp),
                        shape = WinZShapes.Small
                    ) { Icon(Icons.Outlined.Download, uiStr(R.string.ui_download_2), Modifier.size(20.dp)) }
                } else {
                    // Built into the app: nothing to download or delete. Shown exactly like an
                    // in-use Wine/Proton row (disabled grey button) instead of a checkmark.
                    Button(
                        onClick = {},
                        enabled = false,
                        colors = ButtonDefaults.buttonColors(containerColor = destructiveColor(), contentColor = MaterialTheme.colorScheme.onError)
                    ) {
                        Text(uiStr(R.string.ui_built_in))
                    }
                }
            }
            if (busy) {
                Spacer(Modifier.height(9.dp))
                if (installingProgress >= 0) {
                    LinearProgressIndicator(
                        progress = { installingProgress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun InstallProgressCard(label: String?, progress: Int) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .55f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(uiStr(R.string.ui_component_installation), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (progress >= 0) "${label ?: "Installing"} • ${progress}%"
                        else label ?: uiStr(R.string.ui_installing_component),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            }
            Spacer(Modifier.height(10.dp))
            if (progress >= 0) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Surface(Modifier.fillMaxWidth(), shape = WinZShapes.Medium, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(12.dp))
            Text(uiStr(R.string.ui_loading_component_catalog))
        }
    }
}

// Same shape as the Containers screen's top app bar: 64dp, back arrow, title.
@Composable
private fun ManagerTopBar(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = uiStr(R.string.ui_back))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            uiStr(R.string.ui_components),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun ComponentsFooter(
    back: () -> Unit,
    next: () -> Unit,
    landscape: Boolean,
    nextEnabled: Boolean,
    nextLabel: String
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(
                horizontal = if (landscape) 22.dp else 20.dp,
                vertical = if (landscape) 8.dp else 12.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = back,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = WinZShapes.Medium,
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, hairlineColor())
            ) { Text(uiStr(R.string.ui_back)) }
            Button(
                onClick = next,
                enabled = nextEnabled,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = WinZShapes.Medium,
                colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = MaterialTheme.colorScheme.onPrimary)
            ) { Text(nextLabel) }
        }
    }
}
