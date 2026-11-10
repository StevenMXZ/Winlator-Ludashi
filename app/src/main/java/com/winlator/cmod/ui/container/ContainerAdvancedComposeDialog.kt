package com.winlator.cmod.ui.container

import android.app.Dialog
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.winhandler.WinHandler

private data class AdvancedEnvEntry(val name: String, val value: String)
private enum class AdvancedEnvKind { CHECKBOX, SELECT, MULTI, TEXT, NUMBER }
private data class AdvancedEnvSpec(val kind: AdvancedEnvKind, val options: List<String> = emptyList())

private val advancedEnvSpecs = mapOf(
    "ZINK_DESCRIPTORS" to AdvancedEnvSpec(AdvancedEnvKind.SELECT, listOf("auto", "lazy", "cached", "notemplates")),
    "ZINK_DEBUG" to AdvancedEnvSpec(AdvancedEnvKind.MULTI, listOf("nir", "spirv", "tgsi", "validation", "sync", "compact", "noreorder")),
    "MESA_SHADER_CACHE_DISABLE" to AdvancedEnvSpec(AdvancedEnvKind.CHECKBOX, listOf("false", "true")),
    "mesa_glthread" to AdvancedEnvSpec(AdvancedEnvKind.CHECKBOX, listOf("false", "true")),
    "WINEESYNC" to AdvancedEnvSpec(AdvancedEnvKind.CHECKBOX, listOf("0", "1")),
    "TU_DEBUG" to AdvancedEnvSpec(AdvancedEnvKind.MULTI, listOf("forcecb", "nocb", "deck_emu", "startup", "nir", "nobin", "sysmem", "gmem", "forcebin", "layout", "noubwc", "nomultipos", "nolrz", "nolrzfc", "perf", "perfc", "flushall", "syncdraw", "push_consts_per_stage", "rast_order", "unaligned_store", "log_skip_gmem_ops", "dynamic", "bos", "3d_load", "fdm", "noconform", "rd")),
    "DXVK_HUD" to AdvancedEnvSpec(AdvancedEnvKind.MULTI, listOf("scale=0.5", "scale=0.7", "opacity=0.5", "opacity=0.7", "devinfo", "fps", "frametimes", "submissions", "drawcalls", "pipelines", "descriptors", "memory", "gpuload", "version", "api", "cs", "compiler", "samplers")),
    "MESA_EXTENSION_MAX_YEAR" to AdvancedEnvSpec(AdvancedEnvKind.TEXT),
    "VKD3D_SHADER_MODEL" to AdvancedEnvSpec(AdvancedEnvKind.TEXT),
    "WRAPPER_BLIT" to AdvancedEnvSpec(AdvancedEnvKind.TEXT),
    "FD_DEV_FEATURES" to AdvancedEnvSpec(AdvancedEnvKind.TEXT),
    "IR3_SHADER_DEBUG" to AdvancedEnvSpec(AdvancedEnvKind.MULTI, listOf("nouboopt", "nopreamble", "noearlypreamble", "nofp16", "nocache", "spillall", "fullsync", "fullnop", "nodescprefetch", "expandrpt", "noaliastex", "noaliasrt")),
    "WRAPPER_MAX_IMAGE_COUNT" to AdvancedEnvSpec(AdvancedEnvKind.TEXT),
    "MESA_GL_VERSION_OVERRIDE" to AdvancedEnvSpec(AdvancedEnvKind.TEXT),
    "PULSE_LATENCY_MSEC" to AdvancedEnvSpec(AdvancedEnvKind.NUMBER),
    "WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER" to AdvancedEnvSpec(AdvancedEnvKind.CHECKBOX, listOf("0", "1")),
    "WINE_NEW_MEDIASOURCE" to AdvancedEnvSpec(AdvancedEnvKind.CHECKBOX, listOf("0", "1")),
    "GALLIUM_HUD" to AdvancedEnvSpec(AdvancedEnvKind.MULTI, listOf("simple", "fps", "frametime")),
    "WINE_LARGE_ADDRESS_AWARE" to AdvancedEnvSpec(AdvancedEnvKind.CHECKBOX, listOf("0", "1")),
    "WINEDLLOVERRIDES" to AdvancedEnvSpec(AdvancedEnvKind.TEXT)
)

private val componentRows = listOf(
    "direct3d" to "Direct3D",
    "directsound" to "DirectSound",
    "directmusic" to "DirectMusic",
    "directshow" to "DirectShow",
    "directplay" to "DirectPlay",
    "xaudio" to "XAudio",
    "vcrun2010" to "Visual C++ 2010"
)

object ContainerAdvancedComposeDialog {
    @JvmStatic
    fun show(context: Context, containerId: Int, onSaved: Runnable? = null) {
        val dialog: Dialog = ComponentDialog(context, R.style.ContentDialog_Dark)
        val composeView = ComposeView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setContent {
                WinZTheme {
                    AdvancedContainerScreen(
                        containerId = containerId,
                        onCancel = dialog::dismiss,
                        onSaved = {
                            onSaved?.run()
                            dialog.dismiss()
                        }
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.76f }
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
    }
}

@Composable
private fun AdvancedContainerScreen(containerId: Int, onCancel: () -> Unit, onSaved: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = remember(containerId) { ContainerManager(context).getContainerById(containerId) } ?: return
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Environment", "Components", "Startup & Input", "CPU")

    val envRows = remember(container.getEnvVars()) {
        mutableStateListOf<AdvancedEnvEntry>().apply { addAll(parseAdvancedEnv(container.getEnvVars())) }
    }
    val components = remember(container.getWinComponents()) {
        mutableStateMapOf<String, Int>().apply { putAll(parseComponents(container.getWinComponents())) }
    }
    var builtinDecoder by remember { mutableStateOf(container.getBuiltinDecoder()) }
    var softwareDecoding by remember { mutableStateOf(container.getSoftwareDecoding()) }
    var startup by remember { mutableIntStateOf(container.getStartupSelection().toInt().coerceIn(0, 2)) }
    var exclusive by remember { mutableStateOf(container.isExclusiveXInput()) }
    var xinput by remember { mutableStateOf((container.getInputType() and WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()) != 0) }
    var dinput by remember { mutableStateOf((container.getInputType() and WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()) != 0) }
    var syncCpu by remember { mutableStateOf(container.isSyncCpuTopology()) }
    val cpuCount = remember { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }
    val cpu64 = remember(container.getCPUList(true), cpuCount) {
        mutableStateListOf<Boolean>().apply { addAll(cpuSelection(container.getCPUList(true), cpuCount)) }
    }
    val cpu32 = remember(container.getCPUListWoW64(true), cpuCount) {
        mutableStateListOf<Boolean>().apply { addAll(cpuSelection(container.getCPUListWoW64(true), cpuCount)) }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.10f)).padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp).heightIn(max = 820.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("Advanced", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Environment, Windows components, startup, game controller and processor affinity",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    tabs.forEachIndexed { index, title ->
                        if (tab == index) {
                            Button(onClick = { tab = index }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) { Text(title) }
                        } else {
                            OutlinedButton(onClick = { tab = index }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) { Text(title) }
                        }
                    }
                }
                Spacer(Modifier.size(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                ) {
                    when (tab) {
                        0 -> AdvancedEnvironmentPage(envRows)
                        1 -> AdvancedComponentsPage(components, builtinDecoder, softwareDecoding,
                            { builtinDecoder = it }, { softwareDecoding = it })
                        2 -> AdvancedStartupInputPage(
                            startup = startup,
                            onStartup = { startup = it },
                            exclusive = exclusive,
                            onExclusive = { enabled ->
                                exclusive = enabled
                                if (!enabled) {
                                    xinput = true
                                    dinput = true
                                } else if (xinput && dinput) {
                                    dinput = false
                                }
                            },
                            xinput = xinput,
                            onXInput = { enabled ->
                                xinput = enabled
                                if (exclusive && enabled && dinput) dinput = false
                            },
                            dinput = dinput,
                            onDInput = { enabled ->
                                dinput = enabled
                                if (exclusive && enabled && xinput) xinput = false
                            }
                        )
                        else -> AdvancedCpuPage(
                            sync = syncCpu,
                            onSync = { syncCpu = it },
                            cpu64 = cpu64,
                            cpu32 = cpu32
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = {
                            container.setEnvVars(envRows.joinToString(" ") {
                                "${it.name.trim()}=${it.value.trim().replace(" ", "")}"
                            })
                            container.setWinComponents(componentRows.joinToString(",") { (key, _) ->
                                "$key=${components[key] ?: 0}"
                            })
                            container.setBuiltinDecoder(builtinDecoder)
                            container.setSoftwareDecoding(softwareDecoding)
                            container.setStartupSelection(startup.toByte())
                            container.setExclusiveXInput(exclusive)
                            var inputType = 0
                            if (xinput) inputType = inputType or WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()
                            if (dinput) inputType = inputType or WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()
                            container.setInputType(inputType)
                            container.setCPUList(cpu64.indices.filter { cpu64[it] }.joinToString(","))
                            container.setCPUListWoW64(cpu32.indices.filter { cpu32[it] }.joinToString(","))
                            container.setSyncCpuTopology(syncCpu)
                            container.saveData()
                            onSaved()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun AdvancedEnvironmentPage(rows: MutableList<AdvancedEnvEntry>) {
    var addOpen by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) {
        itemsIndexed(rows, key = { index, item -> "${item.name}-$index" }) { index, item ->
            AdvancedEnvRow(
                item = item,
                onValue = { value -> rows[index] = item.copy(value = value) },
                onRemove = { if (index in rows.indices) rows.removeAt(index) }
            )
            if (index != rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        }
        item {
            OutlinedButton(
                onClick = { addOpen = true },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(7.dp))
                Text("Add variable")
            }
        }
    }
    if (addOpen) {
        var name by remember { mutableStateOf("") }
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    val clean = name.trim().replace(" ", "")
                    if (clean.isNotEmpty() && rows.none { it.name == clean }) rows.add(AdvancedEnvEntry(clean, value.trim()))
                    addOpen = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addOpen = false }) { Text("Cancel") } },
            title = { Text("Add environment variable") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(value, { value = it }, label = { Text("Value") }, singleLine = true)
                }
            }
        )
    }
}

@Composable
private fun AdvancedEnvRow(item: AdvancedEnvEntry, onValue: (String) -> Unit, onRemove: () -> Unit) {
    val spec = advancedEnvSpecs[item.name] ?: AdvancedEnvSpec(AdvancedEnvKind.TEXT)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, "Remove") }
            if (spec.kind == AdvancedEnvKind.CHECKBOX) {
                val off = spec.options.getOrElse(0) { "0" }
                val on = spec.options.getOrElse(1) { "1" }
                Switch(checked = item.value == on, onCheckedChange = { onValue(if (it) on else off) })
            }
        }
        if (spec.kind != AdvancedEnvKind.CHECKBOX) {
            Spacer(Modifier.size(5.dp))
            when (spec.kind) {
                AdvancedEnvKind.SELECT -> AdvancedChoice(item.name, item.value, spec.options) { onValue(it) }
                AdvancedEnvKind.MULTI -> AdvancedMultiChoice(item.value, spec.options, onValue)
                AdvancedEnvKind.NUMBER -> OutlinedTextField(
                    value = item.value,
                    onValueChange = { onValue(it.filter(Char::isDigit)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                else -> OutlinedTextField(item.value, onValue, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        }
    }
}

@Composable
private fun AdvancedComponentsPage(values: MutableMap<String, Int>, decoder: String, software: Boolean,
    onDecoder: (String) -> Unit, onSoftware: (Boolean) -> Unit) {
    val entries = listOf("Builtin (Wine)", "Native (Windows)")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) {
        items(componentRows) { (key, label) ->
            val selected = (values[key] ?: 0).coerceIn(0, 1)
            AdvancedChoice(label, entries[selected], entries) { values[key] = entries.indexOf(it).coerceAtLeast(0) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        }
        item { com.winlator.cmod.ui.settings.CodecsSettings(decoder, software, onDecoder, onSoftware) }
    }
}

@Composable
private fun AdvancedStartupInputPage(
    startup: Int,
    onStartup: (Int) -> Unit,
    exclusive: Boolean,
    onExclusive: (Boolean) -> Unit,
    xinput: Boolean,
    onXInput: (Boolean) -> Unit,
    dinput: Boolean,
    onDInput: (Boolean) -> Unit
) {
    val startupEntries = listOf(
        "Normal (Load all services)",
        "Essential (Load only essential services)",
        "Aggressive (Stop services on startup)"
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) {
        item {
            Text("System", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
            AdvancedChoice("Startup Selection", startupEntries[startup.coerceIn(0, 2)], startupEntries) {
                onStartup(startupEntries.indexOf(it).coerceAtLeast(0))
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 10.dp)) }
        item {
            Text("Game Controller", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
            AdvancedToggle("Enable XInput for Games in Wine", xinput, exclusive, onXInput)
            AdvancedToggle("Enable DInput for Games in Wine", dinput, exclusive, onDInput)
            AdvancedToggle("Exclusive Input", exclusive, true, onExclusive)
            if (!exclusive) {
                Text(
                    "With Exclusive Input disabled, XInput and DInput stay enabled together, matching the classic editor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun AdvancedCpuPage(sync: Boolean, onSync: (Boolean) -> Unit, cpu64: MutableList<Boolean>, cpu32: MutableList<Boolean>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { AdvancedToggle("Sync with Wine", sync, true, onSync) }
        item {
            Text("Processor Affinity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            CpuSelector(cpu64)
        }
        item {
            Text("Processor Affinity (32-bit apps)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            CpuSelector(cpu32)
        }
    }
}

@Composable
private fun CpuSelector(values: MutableList<Boolean>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(values.indices.toList()) { index ->
            Surface(
                onClick = { values[index] = !values[index] },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Checkbox(checked = values[index], onCheckedChange = { values[index] = it })
                    Text("CPU$index", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun AdvancedToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun AdvancedChoice(label: String, selected: String, entries: List<String>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { expanded = true }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selected, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { value ->
                DropdownMenuItem(
                    text = { Text(value) },
                    trailingIcon = { if (value == selected) Icon(Icons.Outlined.Check, null) },
                    onClick = { expanded = false; onSelected(value) }
                )
            }
        }
    }
}

@Composable
private fun AdvancedMultiChoice(selected: String, entries: List<String>, onSelected: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Surface(
        onClick = { open = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(selected.ifBlank { "None" }, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.KeyboardArrowDown, null)
        }
    }
    if (open) {
        val draft = remember(selected, open) {
            mutableStateListOf<String>().apply { addAll(selected.split(',').map { it.trim() }.filter { it.isNotEmpty() }) }
        }
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { onSelected(draft.joinToString(",")); open = false }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            text = {
                LazyColumn(Modifier.heightIn(max = 430.dp)) {
                    items(entries) { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = option in draft,
                                onCheckedChange = { enabled -> if (enabled) { if (option !in draft) draft.add(option) } else draft.remove(option) }
                            )
                            Text(option, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        )
    }
}

private fun parseAdvancedEnv(raw: String): List<AdvancedEnvEntry> {
    if (raw.isBlank()) return emptyList()
    return raw.split(' ').mapNotNull { token ->
        val split = token.indexOf('=')
        if (split <= 0) null else AdvancedEnvEntry(token.substring(0, split), token.substring(split + 1))
    }
}

private fun parseComponents(raw: String): Map<String, Int> {
    val result = mutableMapOf<String, Int>()
    raw.split(',').forEach { token ->
        val split = token.indexOf('=')
        if (split > 0) result[token.substring(0, split)] = token.substring(split + 1).toIntOrNull()?.coerceIn(0, 1) ?: 0
    }
    componentRows.forEach { (key, _) -> if (key !in result) result[key] = 0 }
    return result
}

private fun cpuSelection(raw: String?, count: Int): List<Boolean> {
    val selected = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    return List(count) { index -> index.toString() in selected }
}
