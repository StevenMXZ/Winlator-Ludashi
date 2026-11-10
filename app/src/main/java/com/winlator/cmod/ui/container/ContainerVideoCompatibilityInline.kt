package com.winlator.cmod.ui.container

import com.winlator.cmod.ui.uiOptionLabel
import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.box64.Box64PresetManager
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.contents.ContentProfile
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.StringUtils
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.fexcore.FEXCorePresetManager

@Composable
internal fun ContainerWrapperInline(containerId: Int, callbacks: ContainerInlineCallbacks) {
    val context = LocalContext.current
    val container = remember(containerId) { ContainerManager(context).getContainerById(containerId) } ?: return
    val contents = remember { ContentsManager(context).apply { syncContents() } }
    val wineInfo = remember(container.getWineVersion()) { WineInfo.fromIdentifier(context, contents, container.getWineVersion()) }
    val arm64 = wineInfo.isArm64EC()
    val wrapperEntries = remember { context.resources.getStringArray(R.array.dxwrapper_entries) }
    var wrapper by remember { mutableStateOf(findWrapperEntry(wrapperEntries, container.getDXWrapper())) }
    val initial = remember(container.getDXWrapperConfig()) { parseInlineConfig(container.getDXWrapperConfig()) }
    var dxvkVersion by remember { mutableStateOf(initial["version"].orEmpty().ifBlank { DefaultVersion.DXVK }) }
    var vkd3dVersion by remember { mutableStateOf(initial["vkd3dVersion"].orEmpty().ifBlank { DefaultVersion.VKD3D }) }
    var vkd3dLevel by remember { mutableStateOf(initial["vkd3dLevel"] ?: "12_1") }
    var frameRate by remember { mutableStateOf(initial["framerate"] ?: "0") }
    var maxLatency by remember { mutableStateOf(initial["maxFrameLatency"] == "1") }
    var asyncShaders by remember { mutableStateOf(initial["async"] == "1") }
    var asyncCache by remember { mutableStateOf(initial["asyncCache"] == "1") }
    var ddraw by remember { mutableStateOf(initial["ddrawrapper"] ?: "wined3d") }
    var csmt by remember { mutableStateOf(initial["csmt"] != "0") }
    var strictMath by remember { mutableStateOf(initial["strict_shader_math"] == "1") }
    var offscreen by remember { mutableStateOf(initial["OffscreenRenderingMode"] ?: "fbo") }
    var wineRenderer by remember { mutableStateOf(initial["renderer"] ?: "vulkan") }
    var videoMemory by remember { mutableStateOf(initial["videoMemorySize"] ?: "2048") }

    val dxvk = remember(contents, arm64) {
        componentVersions(contents, ContentProfile.ContentType.CONTENT_TYPE_DXVK, dxvkVersion, arm64, DefaultVersion.DXVK)
    }
    val installedDxvk = remember(contents, arm64) {
        installedVersions(contents, ContentProfile.ContentType.CONTENT_TYPE_DXVK, dxvkVersion, arm64, DefaultVersion.DXVK)
    }
    val vkd3d = remember(contents) {
        componentVersions(contents, ContentProfile.ContentType.CONTENT_TYPE_VKD3D, vkd3dVersion, true, DefaultVersion.VKD3D, true)
    }
    val installedVkd3d = remember(contents) {
        installedVersions(contents, ContentProfile.ContentType.CONTENT_TYPE_VKD3D, vkd3dVersion, true, DefaultVersion.VKD3D, true)
    }

    VCPanel {
        Text(uiStr(R.string.ui_directx_wrapper), Modifier.padding(horizontal = 12.dp, vertical = 9.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        VCChoice(uiStr(R.string.dxwrapper), wrapper, wrapperEntries) { wrapper = it }
        VCDivider()
        if (StringUtils.parseIdentifier(wrapper).contains("dxvk", true)) {
            VCDownloadChoice(uiStr(R.string.dxvk_version), dxvkVersion, dxvk, installedDxvk, "DXVK", callbacks) { dxvkVersion = it }
            VCDivider()
            VCDownloadChoice(uiStr(R.string.vkd3d_version), vkd3dVersion, vkd3d, installedVkd3d, "VKD3D", callbacks) { vkd3dVersion = it }
            VCDivider()
            VCChoice(uiStr(R.string.vkd3d_feature_level), vkd3dLevel, arrayOf("12_0", "12_1", "12_2", "11_1", "11_0", "10_1", "10_0", "9_3", "9_2", "9_1")) { vkd3dLevel = it }
            VCField(uiStr(R.string.ui_frame_rate_limit), frameRate) { frameRate = it.filter(Char::isDigit).take(4) }
            VCToggle(uiStr(R.string.ui_max_frame_latency), maxLatency) { maxLatency = it }
            VCToggle(uiStr(R.string.ui_async_shaders), asyncShaders) { asyncShaders = it }
            VCToggle(uiStr(R.string.ui_async_shader_cache), asyncCache) { asyncCache = it }
            VCChoice(uiStr(R.string.ddraw_wrapper), ddraw, arrayOf("wined3d", "cnc-ddraw")) { ddraw = it }
        } else {
            VCToggle(uiStr(R.string.ui_csmt), csmt) { csmt = it }
            VCToggle(uiStr(R.string.strict_shader_math), strictMath) { strictMath = it }
            VCChoice(uiStr(R.string.ui_offscreen_rendering), offscreen, arrayOf("fbo", "backbuffer")) { offscreen = it }
            VCChoice(uiStr(R.string.ui_wined3d_renderer), wineRenderer, arrayOf("gl", "vulkan", "gdi")) { wineRenderer = it }
            VCField(uiStr(R.string.ui_video_memory_mb), videoMemory) { videoMemory = it.filter(Char::isDigit).take(6) }
        }
        OutlinedButton(onClick = callbacks::onManageComponents, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(uiStr(R.string.ui_manage_installed_versions))
        }
        VCSave {
            val parsedWrapper = StringUtils.parseIdentifier(wrapper)
            container.setDXWrapper(parsedWrapper)
            val updates = if (parsedWrapper.contains("dxvk", true)) {
                mapOf(
                    "version" to dxvkVersion,
                    "vkd3dVersion" to vkd3dVersion,
                    "vkd3dLevel" to vkd3dLevel,
                    "framerate" to frameRate,
                    "maxFrameLatency" to if (maxLatency) "1" else "0",
                    "async" to if (asyncShaders) "1" else "0",
                    "asyncCache" to if (asyncCache) "1" else "0",
                    "ddrawrapper" to ddraw
                )
            } else {
                mapOf(
                    "csmt" to if (csmt) "3" else "0",
                    "strict_shader_math" to if (strictMath) "1" else "0",
                    "OffscreenRenderingMode" to offscreen,
                    "renderer" to wineRenderer,
                    "videoMemorySize" to videoMemory
                )
            }
            container.setDXWrapperConfig(mergeInlineConfig(container.getDXWrapperConfig(), updates))
            container.saveData()
            callbacks.onSaved()
        }
    }
}

@Composable
internal fun ContainerCompatibilityInline(containerId: Int, callbacks: ContainerInlineCallbacks) {
    val context = LocalContext.current
    val container = remember(containerId) { ContainerManager(context).getContainerById(containerId) } ?: return
    val contents = remember { ContentsManager(context).apply { syncContents() } }
    val wineInfo = remember(container.getWineVersion()) { WineInfo.fromIdentifier(context, contents, container.getWineVersion()) }
    val arm64 = wineInfo.isArm64EC()

    var emulator32 by remember { mutableStateOf(if (container.getEmulator().equals("FEXCore", true)) "FEXCore" else "WOWBox64") }
    var boxVersion by remember { mutableStateOf(container.getBox64Version().orEmpty().ifBlank { DefaultVersion.BOX64 }) }
    var fexVersion by remember { mutableStateOf(container.getFEXCoreVersion().orEmpty().ifBlank { DefaultVersion.FEXCORE }) }
    var boxPreset by remember { mutableStateOf(container.getBox64Preset()) }
    var fexPreset by remember { mutableStateOf(container.getFEXCorePreset()) }

    val boxPresets = remember { Box64PresetManager.getPresets("box64", context) }
    val fexPresets = remember { FEXCorePresetManager.getPresets(context) }
    val boxType = if (arm64) ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64 else ContentProfile.ContentType.CONTENT_TYPE_BOX64
    val defaultBox = if (arm64) DefaultVersion.WOWBOX64 else DefaultVersion.BOX64
    val boxVersions = remember(contents, arm64) { componentVersions(contents, boxType, boxVersion, true, defaultBox) }
    val installedBox = remember(contents, arm64) { installedVersions(contents, boxType, boxVersion, true, defaultBox) }
    val fexVersions = remember(contents) { componentVersions(contents, ContentProfile.ContentType.CONTENT_TYPE_FEXCORE, fexVersion, true, DefaultVersion.FEXCORE) }
    val installedFex = remember(contents) { installedVersions(contents, ContentProfile.ContentType.CONTENT_TYPE_FEXCORE, fexVersion, true, DefaultVersion.FEXCORE) }

    VCPanel {
        if (arm64) {
            VCReadOnly(uiStr(R.string.ui_64_bit_emulator), "FEXCore")
            VCDivider()
            VCChoice(uiStr(R.string.ui_32_bit_emulator), emulator32, arrayOf("FEXCore", "WOWBox64")) { emulator32 = it }
            VCDivider()
            VCDownloadChoice(uiStr(R.string.fexcore_version), fexVersion, fexVersions, installedFex, "FEXCore", callbacks) { fexVersion = it }
            VCDivider()
            VCPresetChoice(uiStr(R.string.fexcore_preset), fexPreset, fexPresets.map { it.name }.toTypedArray(), fexPresets.map { it.id }.toTypedArray()) { fexPreset = it }
            if (emulator32 == "WOWBox64") {
                VCDivider()
                VCDownloadChoice(uiStr(R.string.ui_wowbox64_version), boxVersion, boxVersions, installedBox, "WOWBox64", callbacks) { boxVersion = it }
                VCDivider()
                VCPresetChoice("Box64 Preset", boxPreset, boxPresets.map { it.name }.toTypedArray(), boxPresets.map { it.id }.toTypedArray()) { boxPreset = it }
            }
        } else {
            VCReadOnly(uiStr(R.string.ui_64_bit_emulator), "Box64")
            VCDivider()
            VCReadOnly(uiStr(R.string.ui_32_bit_emulator), "Box64")
            VCDivider()
            VCDownloadChoice(uiStr(R.string.box64_version), boxVersion, boxVersions, installedBox, "Box64", callbacks) { boxVersion = it }
            VCDivider()
            VCPresetChoice("Box64 Preset", boxPreset, boxPresets.map { it.name }.toTypedArray(), boxPresets.map { it.id }.toTypedArray()) { boxPreset = it }
        }
        OutlinedButton(onClick = callbacks::onManageComponents, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(uiStr(R.string.ui_manage_installed_versions))
        }
        VCSave {
            container.setEmulator(if (arm64 && emulator32 == "FEXCore") "FEXCore" else "Box64")
            container.setBox64Version(boxVersion)
            container.setBox64Preset(boxPreset)
            container.setFEXCoreVersion(fexVersion)
            container.setFEXCorePreset(fexPreset)
            container.saveData()
            callbacks.onSaved()
        }
    }
}

@Composable
private fun VCPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 58.dp, end = 12.dp, bottom = 10.dp),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .20f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .58f))
    ) { Column(Modifier.padding(vertical = 4.dp), content = content) }
}

@Composable
private fun VCReadOnly(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun VCChoice(label: String, selected: String, entries: Array<String>, onSelected: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { open = true }, color = Color.Transparent) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(uiOptionLabel(selected), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { value ->
                DropdownMenuItem(
                    text = { Text(uiOptionLabel(value)) },
                    trailingIcon = { if (value.equals(selected, true)) Icon(Icons.Outlined.Check, null) },
                    onClick = { open = false; onSelected(value) }
                )
            }
        }
    }
}

@Composable
private fun VCDownloadChoice(label: String, selected: String, entries: Array<String>, installed: Set<String>, type: String, callbacks: ContainerInlineCallbacks, onSelected: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { open = true }, color = Color.Transparent) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(uiOptionLabel(selected), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { value ->
                val ready = value.equals("None", true) || installed.any { it.equals(value, true) } || value.equals(selected, true)
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(uiOptionLabel(value), Modifier.weight(1f))
                            if (!ready) Text(uiStr(R.string.ui_download_2), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { open = false; if (ready) onSelected(value) else callbacks.onInstallComponent(type, value) }
                )
            }
        }
    }
}

@Composable
private fun VCPresetChoice(label: String, selectedId: String, names: Array<String>, ids: Array<String>, onSelected: (String) -> Unit) {
    val index = ids.indexOf(selectedId).coerceAtLeast(0)
    VCChoice(label, names.getOrElse(index) { selectedId }, names) { name ->
        val i = names.indexOf(name).coerceAtLeast(0)
        onSelected(ids.getOrElse(i) { selectedId })
    }
}

@Composable
private fun VCField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(10.dp)
    )
}

@Composable
private fun VCToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun VCSave(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(uiStr(R.string.save), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun VCDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .48f))
}

private fun findWrapperEntry(entries: Array<String>, selected: String): String =
    entries.firstOrNull { StringUtils.parseIdentifier(it).equals(selected, true) } ?: entries.firstOrNull().orEmpty()

private fun parseInlineConfig(data: String): LinkedHashMap<String, String> {
    val result = linkedMapOf<String, String>()
    data.split(',').forEach { item ->
        val i = item.indexOf('=')
        if (i > 0) result[item.substring(0, i)] = item.substring(i + 1)
    }
    return result
}

private fun mergeInlineConfig(data: String, updates: Map<String, String>): String {
    val values = parseInlineConfig(data)
    updates.forEach { (key, value) -> values[key] = value }
    return values.entries.joinToString(",") { "${it.key}=${it.value}" }
}

private fun componentVersions(manager: ContentsManager, type: ContentProfile.ContentType, selected: String, allowArm64: Boolean, defaultVersion: String, includeNone: Boolean = false): Array<String> {
    val values = linkedSetOf(defaultVersion)
    manager.getProfiles(type).forEach { profile ->
        if (allowArm64 || !profile.verName.contains("arm64ec", true)) values.add(profile.verName)
    }
    if (selected.isNotBlank()) values.add(selected)
    if (includeNone) values.add("None")
    return values.toTypedArray()
}

private fun installedVersions(manager: ContentsManager, type: ContentProfile.ContentType, selected: String, allowArm64: Boolean, defaultVersion: String, includeNone: Boolean = false): Set<String> {
    val values = linkedSetOf(defaultVersion)
    manager.getInstalledProfiles(type).forEach { profile ->
        if (allowArm64 || !profile.verName.contains("arm64ec", true)) values.add(profile.verName)
    }
    if (selected.isNotBlank()) values.add(selected)
    if (includeNone) values.add("None")
    return values
}
