package com.winlator.cmod.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.remember
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.applyAppFullscreen
import com.winlator.cmod.ui.theme.WinZTheme
import java.io.File

/**
 * Renders only the control UI. The driver and native tests are always loaded
 * in GpuTestWorkerService's dedicated :labs_gpu_test process.
 */
private data class GpuResult(
    val driver: String, val median: List<Double>, val p95: List<Double>,
    val samples: Int, val timestamp: Long
)
private val gpuSuiteLabels = listOf("Geometry", "Shading", "Balanced")

class GpuTestsActivity : AppCompatActivity() {
    private val historyPref by lazy { getSharedPreferences("winlator_labs_gpu_tests", MODE_PRIVATE) }
    private var history by mutableStateOf<List<GpuResult>>(emptyList())
    private var previewPath by mutableStateOf<String?>(null)
    private var previewTick by mutableStateOf(0)

    // Store this new benchmark under its own schema. Do not compare v1/v2 numbers.
    private fun readHistory(): List<GpuResult> {
        return try {
            val values = JSONArray(historyPref.getString("gpu_suite_v3", "[]"))
            (0 until values.length()).mapNotNull { i ->
                val item = values.optJSONObject(i) ?: return@mapNotNull null
                val driver = item.optString("driver", "")
                val m = item.optJSONArray("medians") ?: return@mapNotNull null
                val p = item.optJSONArray("p95") ?: return@mapNotNull null
                if (driver.isBlank() || m.length() != 3 || p.length() != 3)
                    return@mapNotNull null
                val medians = (0..2).map { m.optDouble(it, -1.0) }
                val p95 = (0..2).map { p.optDouble(it, -1.0) }
                if ((medians + p95).any { !it.isFinite() || it <= 0 }) null
                else GpuResult(driver, medians, p95,
                    item.optInt("samples", 0), item.optLong("time", 0L))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveResult(value: String) {
        val fields = value.split('\t')
        if (fields.size < 10 || fields[0] != "SUITE3" || fields[8] != "3") return
        val medians = listOf(1, 3, 5).map { fields[it].toDoubleOrNull() ?: return }
        val p95 = listOf(2, 4, 6).map { fields[it].toDoubleOrNull() ?: return }
        val count = fields[7].toIntOrNull() ?: return
        if (count < 16 || (medians + p95).any { !it.isFinite() || it <= 0 }) return
        val updated = (listOf(GpuResult(driver, medians, p95, count,
            System.currentTimeMillis())) + history).take(60)
        val values = JSONArray()
        updated.forEach { item ->
            values.put(JSONObject().put("driver", item.driver)
                .put("medians", JSONArray(item.median))
                .put("p95", JSONArray(item.p95))
                .put("samples", item.samples)
                .put("time", item.timestamp))
        }
        historyPref.edit().putString("gpu_suite_v3", values.toString()).apply()
        history = updated
    }

    private val availableDrivers by lazy {
        (listOf("System") + File(filesDir, "contents/adrenotools").listFiles().orEmpty()
            .filter { it.isDirectory && File(it, "meta.json").isFile }
            .map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER)).distinct()
    }
    private var driver by mutableStateOf("System")
    private var status by mutableStateOf("Not yet checked")
    private var running by mutableStateOf(false)
    private var ready by mutableStateOf(false)
    private var result by mutableStateOf<String?>(null)
    private var requestId = 0
    private var workerPid = 0
    private var connection: ServiceConnection? = null
    private val timer = Handler(Looper.getMainLooper())
    private val response = Messenger(Handler(Looper.getMainLooper()) { message ->
        val id = message.data.getInt("id", -1)
        if (id == requestId) {
            when (message.what) {
                GpuTestWorkerService.STARTED -> workerPid = message.arg1
                GpuTestWorkerService.PREVIEW -> {
                    val file = message.data.getString("path")
                    if (!file.isNullOrEmpty()) {
                        previewPath = file
                        previewTick++
                    }
                }
                GpuTestWorkerService.FINISHED -> {
                    val value = message.data.getString("answer").orEmpty()
                    running = false
                    workerPid = 0
                    unbind()
                    when {
                        value.startsWith("READY\t") -> {
                            val parts = value.split('\t')
                            ready = true
                            status = "Ready · Vulkan " + parts.getOrElse(2) { "?" }
                        }
                        value.startsWith("SUITE3\t") -> {
                            ready = true
                            result = value
                            status = "Completed · 3 tests"
                            saveResult(value)
                        }
                        else -> {
                            ready = false
                            result = null
                            status = value.removePrefix("ERROR\t").ifEmpty { "Driver unavailable" }
                        }
                    }
                }
            }
        }
        true
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAppFullscreen(this)
        history = readHistory()
        val root = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(root)
        root.addView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                WinZTheme {
                    GpuTestScreen(
                        drivers = availableDrivers, selected = driver, status = status,
                        busy = running, ready = ready, result = result,
                        history = history, previewPath = previewPath, previewTick = previewTick,
                        onBack = { finish() },
                        onSelect = {
                            driver = it
                            ready = false
                            result = null
                            request(checkOnly = true)
                        },
                        onRun = { if (ready && !running) request(checkOnly = false) },
                        onRetry = { request(checkOnly = true) }
                    )
                }
            }
        }, FrameLayout.LayoutParams(-1, -1))
        request(checkOnly = true)
    }

    private fun unbind() {
        connection?.let {
            try { unbindService(it) } catch (_: IllegalArgumentException) {}
        }
        connection = null
    }

    private fun request(checkOnly: Boolean) {
        unbind()
        val id = ++requestId
        running = true
        workerPid = 0
        result = null
        if (checkOnly) previewPath = null
        if (checkOnly) {
            ready = false
            status = "Checking Vulkan device compatibility…"
        } else status = "Running 3 GPU tests…"

        val handler = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (id != requestId) return
                try {
                    val message = Message.obtain(null,
                        if (checkOnly) GpuTestWorkerService.CHECK else GpuTestWorkerService.RUN)
                    message.data = Bundle().apply {
                        putInt("id", id)
                        putString("driver", driver)
                    }
                    message.replyTo = response
                    Messenger(service).send(message)
                } catch (_: RemoteException) {
                    fail(id, "Cannot contact GPU test worker")
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (id == requestId && running) fail(id, "GPU driver crashed or exited")
            }
            override fun onBindingDied(name: ComponentName) {
                if (id == requestId && running) fail(id, "GPU test worker binding failed")
            }
        }
        connection = handler
        if (!bindService(Intent(this, GpuTestWorkerService::class.java),
                handler, Context.BIND_AUTO_CREATE)) {
            fail(id, "Could not start GPU test worker")
            return
        }
        timer.postDelayed({
            if (id == requestId && running) {
                val pid = workerPid
                fail(id, "Vulkan driver timed out")
                if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
            }
        }, if (checkOnly) 12000L else 30000L)
    }

    private fun fail(id: Int, message: String) {
        if (id != requestId) return
        running = false
        ready = false
        result = null
        workerPid = 0
        status = message
        unbind()
    }

    override fun onDestroy() {
        ++requestId
        unbind()
        super.onDestroy()
    }
}

@Composable
private fun GpuTestCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
}

@Composable
private fun GpuTestScreen(
    drivers: List<String>, selected: String, status: String,
    busy: Boolean, ready: Boolean, result: String?,
    history: List<GpuResult>, previewPath: String?, previewTick: Int,
    onBack: () -> Unit, onSelect: (String) -> Unit, onRun: () -> Unit, onRetry: () -> Unit
) {
    val parsed = result?.split('\t').orEmpty()
    val preview = remember(previewPath, previewTick) {
        previewPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
    }
    val latest = remember(history) { history.distinctBy { it.driver } }
    var expanded by remember { mutableStateOf(false) }
    var older by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 15.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back") }
            Column {
                Text("GPU Tests", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                Text("Labs", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item("controls") {
                GpuTestCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Driver", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall)
                            Text(selected, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyLarge)
                        }
                        androidx.compose.foundation.layout.Box {
                            TextButton(onClick = { expanded = true }, enabled = !busy) {
                                Text("Change ▾")
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                drivers.forEach { name ->
                                    DropdownMenuItem(text = { Text(name) }, onClick = {
                                        expanded = false
                                        onSelect(name)
                                    })
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (busy) CircularProgressIndicator(
                            modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                        Text(status, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!ready && !busy) TextButton(onClick = onRetry) { Text("Retry") }
                    }
                    Button(onClick = onRun, enabled = ready && !busy,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Running…" else "Run Benchmark")
                    }
                }
            }

            item("preview-results") {
                GpuTestCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("3D Cube", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("Vulkan · 640²", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(170.dp),
                        color = MaterialTheme.colorScheme.background,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (preview != null) {
                            Image(bitmap = preview, contentDescription = "Vulkan-rendered 3D cube",
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        } else {
                            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center) {
                                Text(if (busy) "Rendering…" else "Run the benchmark to see the cube",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text(
                        "Experimental benchmark. Results may not reflect actual performance in Wine games.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (parsed.size >= 10 && parsed[0] == "SUITE3") {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            gpuSuiteLabels.forEachIndexed { i, label ->
                                val ms = parsed[1 + i * 2].toDoubleOrNull()
                                GpuFpsValue(label, ms, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            item("saved-title") {
                Text("Saved results", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp))
            }
            if (latest.isEmpty()) {
                item("empty") {
                    Text("No saved tests.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(latest, key = { it.driver }) { record ->
                GpuTestCard {
                    Text(record.driver, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        gpuSuiteLabels.forEachIndexed { i, label ->
                            GpuFpsValue(label, record.median[i], Modifier.weight(1f))
                        }
                    }
                }
            }
            if (history.size > latest.size) {
                item("history-toggle") {
                    TextButton(onClick = { older = !older }) {
                        Text(if (older) "Hide older runs" else "Older runs")
                    }
                }
                if (older) {
                    items(history, key = { it.timestamp.toString() + it.driver }) { record ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(record.driver, style = MaterialTheme.typography.bodySmall)
                                Text(SimpleDateFormat("MMM d, HH:mm", Locale.US)
                                    .format(Date(record.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(record.median.joinToString(" / ") {
                                String.format(Locale.US, "%.0f", 1000.0 / it)
                            } + " FPS", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GpuFpsValue(name: String, gpuMs: Double?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
        val fps = gpuMs?.takeIf { it.isFinite() && it > 0.0 }?.let { 1000.0 / it }
        Text(fps?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("FPS", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
    }
}
