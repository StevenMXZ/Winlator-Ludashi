package com.winlator.cmod.ui.settings

import com.winlator.cmod.ui.uiOptionLabel
import com.winlator.cmod.ui.uiStr
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.winlator.cmod.R
import com.winlator.cmod.contents.AdrenotoolsManager
import com.winlator.cmod.contents.ContentProfile
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.contents.RemoteDriverCatalog
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.GPUInformation
import com.winlator.cmod.core.ProtonPackageManager
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.core.WineRuntimeGuard
import com.winlator.cmod.core.WineThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal data class VersionCatalog(val all: List<String>, val installed: Set<String>)
internal data class DriverOption(
    val id: String,
    val label: String,
    val installed: Boolean,
    val remoteUrl: String? = null
)
internal data class WineRuntimeOption(
    val id: String,
    val label: String,
    val type: String,
    val version: String,
    val installed: Boolean
)
internal data class SettingsCatalog(
    val dxvk: VersionCatalog,
    val vkd3d: VersionCatalog,
    val fex: VersionCatalog,
    val box: VersionCatalog,
    val wow: VersionCatalog,
    val drivers: List<DriverOption>,
    val rendererDrivers: Map<String, String>
)

private suspend fun syncRemoteContents(context: Context, manager: ContentsManager) {
    withContext(Dispatchers.IO) {
        runCatching {
            manager.syncContents()
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val url = prefs.getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES)
                ?: ContentsManager.REMOTE_PROFILES
            OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.isSuccessful) response.body?.string()?.let(manager::setRemoteProfiles)
            }
            manager.syncContents()
        }
    }
}

internal suspend fun loadWineRuntimeOptions(context: Context): List<WineRuntimeOption> = withContext(Dispatchers.IO) {
    val manager = ContentsManager(context)
    syncRemoteContents(context, manager)
    val out = LinkedHashMap<String, WineRuntimeOption>()

    listOf(
        ContentProfile.ContentType.CONTENT_TYPE_WINE,
        ContentProfile.ContentType.CONTENT_TYPE_PROTON
    ).forEach { type ->
        manager.getProfiles(type).orEmpty().forEach { profile ->
            val id = ContentsManager.getEntryName(profile)
            out[id] = WineRuntimeOption(
                id = id,
                label = profile.verName,
                type = type.toString(),
                version = profile.verName,
                installed = profile.remoteUrl == null
            )
        }
    }

    ProtonPackageManager.getPackages().forEach { packageInfo ->
        val installed = ProtonPackageManager.isInstalled(context, packageInfo.identifier)
        val existing = out[packageInfo.identifier]
        if (existing?.installed != true) {
            out[packageInfo.identifier] = WineRuntimeOption(
                id = packageInfo.identifier,
                label = packageInfo.title,
                type = ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString(),
                version = packageInfo.title,
                installed = installed
            )
        }
    }

    val mainId = WineInfo.MAIN_WINE_VERSION.identifier()
    if (WineRuntimeGuard.isBundledMainInstalled(context) && out[mainId] == null) {
        out[mainId] = WineRuntimeOption(
            id = mainId,
            label = ProtonPackageManager.getPackage(mainId)?.title ?: mainId,
            type = ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString(),
            version = WineInfo.MAIN_WINE_VERSION.fullVersion(),
            installed = true
        )
    }

    out.values.sortedWith(
        compareByDescending<WineRuntimeOption> { it.id == WineInfo.MAIN_WINE_VERSION.identifier() }
            .thenByDescending { it.installed }
            .thenBy { it.type.lowercase() }
            .thenBy { it.label.lowercase() }
    )
}

internal suspend fun installWineRuntimeComponent(context: Context, option: WineRuntimeOption): String? {
    if (option.installed) return option.id

    ProtonPackageManager.getPackage(option.id)?.let { packageInfo ->
        val archive = File(context.cacheDir, "winz-${System.nanoTime()}-${packageInfo.fileName}")
        val installed = withContext(Dispatchers.IO) {
            try {
                ProtonPackageManager.downloadPackage(packageInfo, archive) { _ -> } &&
                    ProtonPackageManager.installPackage(context, packageInfo.identifier, archive)
            } finally {
                archive.delete()
            }
        }
        return packageInfo.identifier.takeIf {
            installed && ProtonPackageManager.isInstalled(context, packageInfo.identifier)
        }
    }

    val installedName = installRuntimeComponent(context, option.type, option.version) ?: return null
    val manager = ContentsManager(context)
    manager.syncContents()
    val type = ContentProfile.ContentType.getTypeByName(option.type) ?: return null
    return manager.getInstalledProfiles(type)
        .filter { it.verName == installedName }
        .maxByOrNull { it.verCode }
        ?.let { ContentsManager.getEntryName(it) }
}

internal suspend fun loadSettingsCatalog(
    context: Context,
    arm64: Boolean,
    selectedDxvk: String = "",
    selectedVkd3d: String = "",
    selectedFex: String = "",
    selectedBox: String = "",
    selectedDriver: String = ""
): SettingsCatalog = withContext(Dispatchers.IO) {
    val manager = ContentsManager(context)
    syncRemoteContents(context, manager)

    fun versions(
        type: ContentProfile.ContentType,
        bundled: Iterable<String>,
        selected: String,
        filterArm: Boolean = true
    ): VersionCatalog {
        val allowed: (String) -> Boolean = { value ->
            !filterArm || arm64 || !value.contains("arm64ec", ignoreCase = true)
        }
        val all = linkedSetOf<String>()
        bundled.filter { it.isNotBlank() && allowed(it) }.forEach(all::add)
        runCatching { manager.getProfiles(type) }.getOrNull().orEmpty()
            .map { it.verName }.filter(allowed).forEach(all::add)
        if (selected.isNotBlank()) all.add(selected)

        val installed = linkedSetOf<String>()
        bundled.filter { it.isNotBlank() && allowed(it) }.forEach(installed::add)
        runCatching { manager.getInstalledProfiles(type) }.getOrNull().orEmpty()
            .map { it.verName }.filter(allowed).forEach(installed::add)
        return VersionCatalog(all.toList(), installed)
    }

    val adreno = AdrenotoolsManager(context)
    val rendererDrivers = linkedMapOf("system" to "System")
    val driverOptions = linkedMapOf<String, DriverOption>()

    context.resources.getStringArray(R.array.wrapper_graphics_driver_version_entries).forEach { version ->
        if (version.equals("System", ignoreCase = true) || GPUInformation.isDriverSupported(version, context)) {
            driverOptions[version.lowercase()] = DriverOption(version, version, true)
        }
    }
    runCatching { adreno.enumerateRendererDrivers() }.getOrNull().orEmpty().forEach { id ->
        val label = listOf(adreno.getDriverName(id), adreno.getDriverVersion(id))
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { id }
        rendererDrivers[id] = label
        driverOptions[id.lowercase()] = DriverOption(id, label, true)
    }
    runCatching { RemoteDriverCatalog.load(context) }.getOrNull().orEmpty().forEach { remote ->
        val alreadyInstalled = driverOptions.values.any {
            it.id.equals(remote.name, ignoreCase = true) || it.label.equals(remote.name, ignoreCase = true)
        }
        if (!alreadyInstalled) {
            driverOptions["remote:${remote.name}:${remote.url}"] =
                DriverOption(remote.name, remote.name, false, remote.url)
        }
    }
    if (selectedDriver.isNotBlank() && driverOptions.values.none { it.id.equals(selectedDriver, ignoreCase = true) }) {
        driverOptions["selected:${selectedDriver.lowercase()}"] = DriverOption(selectedDriver, selectedDriver, true)
    }

    SettingsCatalog(
        dxvk = versions(
            ContentProfile.ContentType.CONTENT_TYPE_DXVK,
            context.resources.getStringArray(R.array.dxvk_version_entries).toList(),
            selectedDxvk
        ),
        vkd3d = versions(
            ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
            context.resources.getStringArray(R.array.vkd3d_version_entries).toList(),
            selectedVkd3d
        ),
        fex = versions(
            ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
            listOf(DefaultVersion.FEXCORE), selectedFex, false
        ),
        box = versions(
            ContentProfile.ContentType.CONTENT_TYPE_BOX64,
            listOf(DefaultVersion.BOX64), selectedBox, false
        ),
        wow = versions(
            ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64,
            listOf(DefaultVersion.WOWBOX64), selectedBox, false
        ),
        drivers = driverOptions.values.toList(),
        rendererDrivers = rendererDrivers
    )
}

internal suspend fun installRuntimeComponent(
    context: Context,
    typeName: String,
    version: String
): String? {
    val manager = ContentsManager(context)
    val profile = withContext(Dispatchers.IO) {
        try {
            syncRemoteContents(context, manager)
            val type = ContentProfile.ContentType.getTypeByName(typeName) ?: return@withContext null
            manager.getProfiles(type).orEmpty().firstOrNull {
                it.verName == version && it.remoteUrl != null
            }
        } catch (_: Exception) {
            null
        }
    } ?: return null

    val archive = File(context.cacheDir, "winz-${System.nanoTime()}")
    val downloaded = withContext(Dispatchers.IO) {
        try {
            OkHttpClient().newCall(Request.Builder().url(profile.remoteUrl).build()).execute().use { response ->
                if (!response.isSuccessful || response.body == null) {
                    false
                } else {
                    response.body!!.byteStream().use { input ->
                        FileOutputStream(archive).use { output -> input.copyTo(output, 64 * 1024) }
                    }
                    true
                }
            }
        } catch (_: Exception) {
            false
        }
    }
    if (!downloaded) return null

    val result = suspendCoroutine<String?> { continuation ->
        manager.extraContentFile(Uri.fromFile(archive), object : ContentsManager.OnInstallFinishedCallback {
            override fun onFailed(reason: ContentsManager.InstallFailedReason, error: Exception) {
                continuation.resume(null)
            }

            override fun onSucceed(extracted: ContentProfile) {
                manager.finishInstallContent(extracted, object : ContentsManager.OnInstallFinishedCallback {
                    override fun onFailed(reason: ContentsManager.InstallFailedReason, error: Exception) {
                        continuation.resume(
                            if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST) profile.verName else null
                        )
                    }

                    override fun onSucceed(installed: ContentProfile) {
                        continuation.resume(installed.verName)
                    }
                })
            }
        })
    }
    archive.delete()
    ContentsManager.cleanTmpDir(context)
    manager.syncContents()
    return result
}

internal suspend fun installAdrenoDriver(context: Context, option: DriverOption): String? =
    withContext(Dispatchers.IO) {
        if (option.remoteUrl != null) {
            RemoteDriverCatalog.install(context, option.remoteUrl).takeIf { it.isNotBlank() }
        } else {
            option.id.takeIf { option.installed }
        }
    }

internal fun readConfig(config: String?, key: String, separator: Char): String {
    config.orEmpty().split(separator).forEach { token ->
        val index = token.indexOf('=')
        if (index > 0 && token.substring(0, index).trim() == key) {
            return token.substring(index + 1).trim()
        }
    }
    return ""
}

internal fun writeConfig(config: String?, key: String, value: String, separator: Char): String {
    val out = ArrayList<String>()
    var found = false
    config.orEmpty().split(separator).filter { it.isNotBlank() }.forEach { token ->
        val index = token.indexOf('=')
        if (index > 0 && token.substring(0, index).trim() == key) {
            out += "$key=$value"
            found = true
        } else {
            out += token.trim()
        }
    }
    if (!found) out += "$key=$value"
    return out.joinToString(separator.toString())
}

internal fun normalizeResolution(value: String): String =
    value.replace(Regex("\\s*\\(.*\\)$"), "").trim()

private fun settingDisplayLabel(value: String): String =
    if (value == "Lanczos 2 (16-tap)") "Lanczos 2" else uiOptionLabel(value)

private fun settingFieldLabel(label: String): String = when (label) {
    uiStr(R.string.graphics_driver) -> uiStr(R.string.ui_opengl_driver)
    uiStr(R.string.ui_driver_version) -> uiStr(R.string.ui_vulkan_driver)
    else -> label
}

private fun settingChoiceEntries(label: String, entries: List<String>): List<String> =
    if (label == uiStr(R.string.graphics_driver)) listOf("Zink", "Freedreno") else entries
private fun settingChoiceSelected(label: String, selected: String): String =
    if (label == uiStr(R.string.graphics_driver) && selected.equals("wrapper", ignoreCase = true)) "Zink" else selected

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f))
    ) {
        Column { content() }
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .52f)
    )
}

@Composable
private fun WallpaperPreview() {
    val context = LocalContext.current
    val file = WineThemeManager.getUserWallpaperFile(context)
    val stamp = if (file.isFile) file.lastModified() else 0L
    val bitmap = remember(stamp) {
        if (file.isFile) BitmapFactory.decodeFile(file.path) else null
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = uiStr(R.string.ui_wallpaper_preview),
                modifier = Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Image(
                painter = painterResource(R.drawable.wallpaper),
                contentDescription = uiStr(R.string.ui_wallpaper_preview),
                modifier = Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
internal fun SettingChoice(
    label: String,
    selected: String,
    entries: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLabel = settingFieldLabel(label)
    val displaySelected = settingChoiceSelected(label, selected)
    val displayEntries = settingChoiceEntries(label, entries)
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Surface(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(displayLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            settingDisplayLabel(displaySelected),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(Icons.Outlined.KeyboardArrowDown, null)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 220.dp, max = 420.dp).heightIn(max = 480.dp)
            ) {
                displayEntries.distinct().forEach { value ->
                    DropdownMenuItem(
                        text = { Text(settingDisplayLabel(value), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { if (value == displaySelected) Icon(Icons.Outlined.Check, null) },
                        onClick = {
                            expanded = false
                            onSelected(value)
                        }
                    )
                }
            }
        }
        if (label == uiStr(R.string.ui_desktop_background) && selected.equals("Image", ignoreCase = true)) {
            WallpaperPreview()
        }
    }
}

@Composable
internal fun SettingMappedChoice(
    label: String,
    selectedId: String,
    entries: Map<String, String>,
    onSelectedId: (String) -> Unit
) {
    val shown = entries[selectedId] ?: selectedId
    SettingChoice(label, shown, entries.values.toList()) { selectedLabel ->
        onSelectedId(entries.entries.firstOrNull { it.value == selectedLabel }?.key ?: selectedId)
    }
}

@Composable
internal fun SettingWineRuntimeChoice(
    label: String,
    selectedId: String,
    options: List<WineRuntimeOption>,
    installing: Set<String>,
    onInstall: (WineRuntimeOption) -> Unit,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.firstOrNull { it.id == selectedId }
    Box(Modifier.fillMaxWidth()) {
        Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        selectedOption?.label ?: selectedId.ifBlank { uiStr(R.string.ui_choose_a_version) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 260.dp, max = 460.dp).heightIn(max = 500.dp)
        ) {
            options.forEach { option ->
                val busy = "wine:${option.id}" in installing
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (option.installed) 1f else .52f))
                            Text(
                                if (option.installed) option.type else if (busy) uiStr(R.string.ui_1_s_downloading, option.type) else uiStr(R.string.ui_1_s_download, option.type),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    trailingIcon = {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else if (option.installed && option.id == selectedId) Icon(Icons.Outlined.Check, null)
                    },
                    onClick = {
                        if (option.installed) {
                            expanded = false
                            onSelected(option.id)
                        } else if (!busy) {
                            onInstall(option)
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun SettingInstallChoice(
    label: String,
    selected: String,
    catalog: VersionCatalog,
    installing: Set<String>,
    prefix: String,
    onInstall: (String) -> Unit,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selected.ifBlank { uiStr(R.string.ui_choose_a_version) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 240.dp, max = 440.dp).heightIn(max = 480.dp)
        ) {
            catalog.all.forEach { value ->
                val available = catalog.installed.any { it.equals(value, ignoreCase = true) } || value == selected
                val busy = "$prefix:$value" in installing
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(value, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (available) 1f else .52f))
                            if (!available) Text(if (busy) uiStr(R.string.ui_downloading) else uiStr(R.string.ui_download_2), style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    trailingIcon = {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else if (value == selected) Icon(Icons.Outlined.Check, null)
                    },
                    onClick = {
                        if (available) {
                            expanded = false
                            onSelected(value)
                        } else if (!busy) {
                            onInstall(value)
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun SettingDriverChoice(
    label: String,
    selected: String,
    options: List<DriverOption>,
    installing: Set<String>,
    onInstall: (DriverOption) -> Unit,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.firstOrNull { it.id.equals(selected, ignoreCase = true) }
    Box(Modifier.fillMaxWidth()) {
        Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(settingFieldLabel(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        selectedOption?.label ?: selected,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 260.dp, max = 460.dp).heightIn(max = 500.dp)
        ) {
            options.forEach { option ->
                val busy = "driver:${option.remoteUrl ?: option.id}" in installing
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (option.installed) 1f else .52f))
                            if (!option.installed) Text(if (busy) uiStr(R.string.ui_downloading) else uiStr(R.string.ui_download_2), style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    trailingIcon = {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else if (option.installed && option.id.equals(selected, ignoreCase = true)) Icon(Icons.Outlined.Check, null)
                    },
                    onClick = {
                        if (option.installed) {
                            expanded = false
                            onSelected(option.id)
                        } else if (!busy) {
                            onInstall(option)
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun SettingToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChanged: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChanged, enabled = enabled)
    }
}

@Composable
internal fun SettingText(
    label: String,
    value: String,
    minLines: Int = 1,
    onChanged: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChanged,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        minLines = minLines,
        maxLines = if (minLines > 1) 5 else 1,
        shape = RoundedCornerShape(10.dp)
    )
}

@Composable
internal fun CpuSelectorRow(
    title: String,
    selected: List<Boolean>,
    onToggle: (Int, Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            items(selected.indices.toList(), key = { it }) { index ->
                Surface(
                    onClick = { onToggle(index, !selected[index]) },
                    shape = RoundedCornerShape(9.dp),
                    color = if (selected[index]) Color.White else Color.Transparent,
                    contentColor = if (selected[index]) Color.Black else MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, if (selected[index]) Color.White else MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        uiStr(R.string.ui_cpu_1_s, index),
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                        color = if (selected[index]) Color.Black else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (selected[index]) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
