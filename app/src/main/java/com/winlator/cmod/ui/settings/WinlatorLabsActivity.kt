package com.winlator.cmod.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.core.GPUInformation
import com.winlator.cmod.ui.applyAppFullscreen
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class WinlatorLabsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAppFullscreen(this)
        val root = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(root)
        root.addView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { WinZTheme { LabsScreen { finish() } } }
        }, FrameLayout.LayoutParams(-1, -1))
    }
    override fun onResume() {
        super.onResume()
        applyAppFullscreen(this)
    }
}

private data class Capability(val key: String, val value: String)

private data class DriverReport(
    val driver: String,
    val sections: Map<String, List<Capability>>,
    val error: String? = null
) {
    fun get(section: String): List<Capability> = sections[section].orEmpty()
    fun export(): String = buildString {
        appendLine("Labs - Graphics Information")
        appendLine("Selected driver: " + driver)
        error?.let { appendLine("Error: " + it) }
        get("warning").forEach { appendLine("Warning: " + it.value) }
        listOf("overview", "feature", "extension", "limit", "format").forEach { section ->
            appendLine("\n[" + section + "]")
            get(section).forEach { entry -> appendLine(entry.key + ": " + entry.value) }
        }
        appendLine("\nReported capabilities, not features enabled in a running game.")
    }
}

private fun installedDrivers(context: Context): List<String> {
    val dir = File(context.filesDir, "contents/adrenotools")
    return (listOf("System") + dir.listFiles()
        .orEmpty()
        .filter { it.isDirectory && File(it, "meta.json").isFile }
        .map { it.name }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)).distinct()
}

private fun queryDriver(context: Context, driver: String): DriverReport {
    val rows = mutableMapOf<String, MutableList<Capability>>()
    var error: String? = null
    try {
        GPUInformation.queryCapabilities(driver, context).forEach { encoded ->
            val fields = encoded.split('\t', limit = 3)
            if (fields.size == 3) {
                if (fields[0] == "error") error = fields[2]
                else rows.getOrPut(fields[0]) { mutableListOf() }.add(Capability(fields[1], fields[2]))
            }
        }
    } catch (e: Exception) {
        error = e.message ?: "Vulkan probe failed"
    } catch (e: LinkageError) {
        error = e.message ?: "Native probe unavailable"
    }
    return DriverReport(driver, rows,
        error ?: if (rows.isEmpty())
            "The driver returned no Vulkan capabilities. It may have failed to initialize or provide the required Vulkan functions."
        else null)
}

private fun copyReport(context: Context, report: DriverReport) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Labs report", report.export()))
    Toast.makeText(context, "Driver report copied", Toast.LENGTH_SHORT).show()
}

@Composable
private fun LabsScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val drivers = remember { installedDrivers(context) }
    var page by remember { mutableIntStateOf(0) }
    var driver by remember { mutableStateOf("System") }
    var otherDriver by remember { mutableStateOf(drivers.getOrElse(1) { "System" }) }
    var first by remember { mutableStateOf<DriverReport?>(null) }
    var second by remember { mutableStateOf<DriverReport?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val reports = remember { java.util.concurrent.ConcurrentHashMap<String, DriverReport>() }
    val tabs = listOf("Overview", "Features", "Extensions", "Limits", "Formats")
    val sections = listOf("overview", "feature", "extension", "limit", "format")

    fun back() { if (page == 0) onExit() else page = 0 }
    BackHandler { back() }

    // The legacy Vulkan JNI loader shares instance and library handles.
    // Never run two driver probes simultaneously.
    LaunchedEffect(page, driver, otherDriver, refresh) {
        if (page != 0) {
            first = null
            second = null
            val result = withContext(Dispatchers.IO) {
                fun cached(name: String): DriverReport =
                    reports[name] ?: queryDriver(context.applicationContext, name).also {
                        if (it.error == null) reports[name] = it
                    }
                val a = cached(driver)
                val b = if (page == 2) cached(otherDriver) else null
                Pair(a, b)
            }
            first = result.first
            second = result.second
        }
    }
    LaunchedEffect(driver, tab) { filter = "" }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = ::back) { Icon(Icons.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(
                    when (page) { 1 -> "Graphics Information"; 2 -> "Compare Drivers"; else -> "Labs" },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (page == 0) "Experimental tools and diagnostics" else "Labs",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (page != 0) {
                IconButton(onClick = {
                    reports.remove(driver)
                    reports.remove(otherDriver)
                    refresh++
                }) { Icon(Icons.Outlined.Refresh, "Refresh driver information") }
            }
            if (page == 1 && first != null) {
                IconButton(onClick = { copyReport(context, first!!) }) {
                    Icon(Icons.Outlined.ContentCopy, "Copy report")
                }
            }
        }

        when (page) {
            0 -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { Title("GRAPHICS & DRIVERS") }
                item {
                    LabsCard {
                        LabsLink("Graphics Information", "GPU, Vulkan features, extensions, limits and formats") { page = 1 }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        LabsLink("Compare Drivers", "Compare capabilities of two installed drivers") { page = 2 }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        LabsLink("GPU Tests", "Validate and benchmark compatible Vulkan drivers") {
                            context.startActivity(Intent(context, GpuTestsActivity::class.java))
                        }
                    }
                }
            }
            1 -> {
                DriverPicker("Selected driver", driver, drivers) { driver = it }
                ScrollableTabRow(
                    selectedTabIndex = tab,
                    edgePadding = 8.dp,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
                if (first == null) Loading()
                else if (first!!.error != null) ErrorCard(first!!.error!!)
                else if (tab == 0) Overview(first!!)
                else {
                    val source = first!!.get(sections[tab])
                    val visible = remember(source, filter) {
                        source.filter { it.key.contains(filter.trim(), ignoreCase = true) ||
                            it.value.contains(filter.trim(), ignoreCase = true) }
                    }
                    Column {
                        if (tab == 2 && first!!.get("warning").isNotEmpty()) {
                            first!!.get("warning").forEach {
                                LabsNote(it.value)
                            }
                        }
                        if (tab == 4) {
                            Text("Support for sampled textures using optimal tiling",
                                modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            value = filter, onValueChange = { filter = it },
                            label = { Text("Search " + tabs[tab].lowercase()) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            item {
                                Text(visible.size.toString() + " of " + source.size + " entries",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            items(visible, key = { it.key }) { item ->
                                ValueRow(item.key, item.value)
                            }
                        }
                    }
                }
            }
            else -> {
                CompareDrivers(
                    a = first, b = second, selectedA = driver, selectedB = otherDriver,
                    drivers = drivers, onA = { driver = it }, onB = { otherDriver = it }
                )

            }
        }
    }
}

@Composable
private fun DriverPicker(title: String, current: String, drivers: List<String>, select: (String) -> Unit) {
    var opened by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            TextButton(onClick = { opened = true }) { Text(current + " ▾") }
            DropdownMenu(expanded = opened, onDismissRequest = { opened = false }) {
                drivers.forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        opened = false
                        select(name)
                    })
                }
            }
        }
    }
}

@Composable
private fun LabsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) { Column { content() } }
}

@Composable
private fun LabsLink(title: String, subtitle: String, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = click).padding(15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Memory, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null)
    }
}

@Composable
private fun Title(title: String) {
    Text(title, modifier = Modifier.padding(vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LabsNote(message: String) {
    LabsCard {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorCard(message: String) {
    Column(Modifier.padding(16.dp)) {
        LabsCard {
            Column(Modifier.padding(16.dp)) {
                Text("Driver query failed",
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
                Text("Try the System driver or inspect the installed driver's package and Vulkan dependencies.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodySmall,
                color = when (value) {
                    "Unsupported", "Unavailable" -> MaterialTheme.colorScheme.error
                    "Supported", "Available", "Sampled" -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Overview(report: DriverReport) {
    val context = LocalContext.current
    val details = report.get("overview") + listOf(
        Capability("Driver", report.driver),
        Capability("Source", if (report.driver == "System") "Android System" else "Custom (AdrenoTools)")
    )
    val features = report.get("feature").associate { it.key to it.value }
    val extensions = report.get("extension").map { it.key }.toSet()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (report.get("warning").isNotEmpty()) {
            item {
                report.get("warning").forEach { LabsNote(it.value) }
            }
        }
        item {
            LabsCard {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Current Driver", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    details.forEach {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth()) {
                            Text(it.key, modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(it.value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item {
            LabsCard {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Driver Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    SummaryRow("Features", report.get("feature").count { it.value == "Supported" }.toString() +
                        " / " + report.get("feature").size + " supported")
                    SummaryRow("Extensions", report.get("extension").size.toString() + " available")
                    SummaryRow("Limits", report.get("limit").size.toString() + " queried")
                    SummaryRow("Formats", report.get("format").count { it.value == "Supported" }.toString() +
                        " / " + report.get("format").size + " sampled textures supported")
                }
            }
        }
        item {
            LabsCard {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Compatibility Checks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    listOf("storageBuffer16BitAccess", "descriptorIndexing",
                        "timelineSemaphore", "dynamicRendering").forEach {
                        SummaryRow(it, features[it] ?: "Not queried")
                    }
                    SummaryRow("VK_KHR_swapchain",
                        if ("VK_KHR_swapchain" in extensions) "Available" else "Not reported")
                }
            }
        }
        item {
            LabsNote("This page shows driver capabilities, not features enabled by a running game. " +
                "Compatibility checks do not guarantee DXVK or VKD3D support.")
        }
        item {
            Surface(
                onClick = { copyReport(context, report) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ContentCopy, null)
                    Spacer(Modifier.width(12.dp))
                    Text("Copy Driver Report", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, result: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(result, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}


@Composable
private fun CompareDrivers(
    a: DriverReport?, b: DriverReport?, selectedA: String, selectedB: String,
    drivers: List<String>, onA: (String) -> Unit, onB: (String) -> Unit
) {
    val categories = listOf("Differences", "Features", "Extensions", "Formats", "Limits")
    val sectionKeys = listOf("", "feature", "extension", "format", "limit")
    var selected by remember { mutableIntStateOf(0) }
    var differencesOnly by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    fun changes(section: String): Int {
        val left = a?.get(section).orEmpty().associate { it.key to it.value }
        val right = b?.get(section).orEmpty().associate { it.key to it.value }
        return (left.keys + right.keys).toSet().count { left[it] != right[it] }
    }

    // Recompute only after both probes complete; selectors remain scrollable while loading.
    val featureChanges = if (a != null && b != null) changes("feature") else 0
    val extensionChanges = if (a != null && b != null) changes("extension") else 0
    val formatChanges = if (a != null && b != null) changes("format") else 0
    val limitsChanges = if (a != null && b != null) changes("limit") else 0
    val allSections = if (selected == 0) sectionKeys.drop(1) else listOf(sectionKeys[selected])
    val entries = if (a == null || b == null) emptyList() else allSections.flatMap { section ->
        val aMap = a!!.get(section).associate { it.key to it.value }
        val bMap = b!!.get(section).associate { it.key to it.value }
        (aMap.keys + bMap.keys).toSortedSet()
            .filter { key ->
                (selected == 0 || differencesOnly).not() || aMap[key] != bMap[key]
            }
            .map { key ->
                Triple(
                    (if (selected == 0) section.uppercase() + " • " else "") + key,
                    aMap[key] ?: "Not reported",
                    bMap[key] ?: "Not reported"
                )
            }
    }.filter { item ->
        item.first.contains(query.trim(), ignoreCase = true) ||
            item.second.contains(query.trim(), ignoreCase = true) ||
            item.third.contains(query.trim(), ignoreCase = true)
    }

    // Single scroll owner. In landscape, header/driver pickers/filters can scroll
    // completely out of view and never consume the list's vertical space.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item("drivers") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column {
                    DriverPicker("Driver A", selectedA, drivers, onA)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DriverPicker("Driver B", selectedB, drivers, onB)
                }
            }
        }
        if (a != null && b != null) {
            if (a.error != null || b.error != null) {
                item("error") { ErrorCard(listOfNotNull(a.error, b.error).joinToString("; ")) }
            } else {
                item("summary") {
                    LabsCardComparisonSummary(a, b, featureChanges, extensionChanges, formatChanges, limitsChanges)
                }
            }
        } else {
            item("loading") {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        if (a != null && b != null && a.error == null && b.error == null) {
            item("tabs") {
                ScrollableTabRow(
                    selectedTabIndex = selected, edgePadding = 0.dp,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    categories.forEachIndexed { index, title ->
                        Tab(selected = selected == index, onClick = {
                            selected = index
                            query = ""
                        }, text = { Text(title) })
                    }
                }
            }
            item("search") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text("Search comparison") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    if (selected != 0) {
                        FilterChip(selected = differencesOnly,
                            onClick = { differencesOnly = !differencesOnly },
                            label = { Text("Changed") })
                    }
                }
            }
            item("count") {
                Text(
                    entries.size.toString() + if (selected == 0 || differencesOnly) " differences" else " entries",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (entries.isEmpty()) item("empty") { LabsNote("No differences found for the current filter.") }
            items(entries, key = { it.first }) { entry ->
                CompareValueRow(entry.first, entry.second, entry.third)
            }
        }
    }

}

@Composable
private fun LabsCardComparisonSummary(
    a: DriverReport, b: DriverReport, features: Int, extensions: Int, formats: Int, limits: Int
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Summary", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                DriverSummaryLine("A", a)
                DriverSummaryLine("B", b)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompareMetric("Features", features, Modifier.weight(1f))
                CompareMetric("Extensions", extensions, Modifier.weight(1f))
                CompareMetric("Formats", formats, Modifier.weight(1f))
                CompareMetric("Limits", limits, Modifier.weight(1f))
            }

        }
    }
}

@Composable
private fun DriverSummaryLine(label: String, report: DriverReport) {
    val apiVersion = report.get("overview").firstOrNull { it.key == "Vulkan API" }?.value
        ?: "Unknown"
    val extensions = report.get("extension").size
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(report.driver, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("Vk $apiVersion • $extensions ext",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CompareMetric(name: String, count: Int, modifier: Modifier) {
    Column(modifier) {
        Text(count.toString(), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(name, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CompareValueRow(name: String, valueA: String, valueB: String) {
    LabsCard {
        Column(Modifier.fillMaxWidth().padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompareSide("A", valueA, Modifier.weight(1f))
                CompareSide("B", valueB, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CompareSide(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Driver " + label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = when (value) {
                "Supported", "Available" -> MaterialTheme.colorScheme.primary
                "Unsupported", "Unavailable" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            }, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
