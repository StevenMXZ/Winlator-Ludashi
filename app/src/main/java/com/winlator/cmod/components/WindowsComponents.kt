@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.winlator.cmod.components

import android.content.Context
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.winlator.cmod.container.Container
import com.winlator.cmod.core.ContainerSessionGate
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** All entry points operate on the same container prefix, including entry from a game's editor. */
object WindowsComponents {
    @JvmStatic fun show(context: Context, container: Container) {
        val dialog = ComponentDialog(context)
        dialog.setContentView(ComposeView(context).apply {
            setContent { WinZTheme { ComponentsPage(container, dialog::dismiss) } }
        })
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

/** One compact row; the catalog itself does not expand inside Advanced. */
@Composable fun WindowsComponentsEntry(container: Container?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    TextButton(onClick = { container?.let { WindowsComponents.show(context, it) } },
        enabled = container != null, modifier = Modifier.fillMaxWidth()) {
        Text("Windows Components", Modifier.weight(1f))
        Text(if (container == null) "Save first" else "Manage")
    }
}

@Composable private fun ComponentsPage(container: Container, close: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<List<Component>>(emptyList()) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<Component?>(null) }
    val prefs = remember { context.getSharedPreferences("component_installs", Context.MODE_PRIVATE) }
    fun refreshInstalled() {
        installed = prefs.getStringSet("c${container.id}", emptySet()).orEmpty().toSet() +
            catalog.filter { ComponentUndo.journalsFor(container.rootDir, it.name).isNotEmpty() }.map { it.name }
    }
    LaunchedEffect(Unit) {
        catalog = withContext(Dispatchers.IO) { ComponentCatalog.load() }
        refreshInstalled(); loading = false
        if (catalog.isEmpty()) message = "Could not load the component catalog. Check your connection and reopen this page."
    }
    fun operate(component: Component, remove: Boolean) {
        if (busy != null) return
        if (!ContainerSessionGate.edit(container.id)) { message = "Close this container's Wine session before changing components."; return }
        busy = component.name; progress = 0f; stage = if (remove) "Restoring files and registry" else "Preparing installation"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (remove) {
                        val dependents = catalog.filter { it.name in installed && component.name in it.dependencies }.map { it.name }
                        check(dependents.isEmpty()) { "Required by: ${dependents.joinToString()}. Remove these first." }
                        val journals = ComponentUndo.journalsFor(container.rootDir, component.name)
                        check(journals.isNotEmpty()) { "No restore history. Reinstall this component once before removing it." }
                        val notes = journals.flatMap { ComponentUndo.revert(container.rootDir, it) }
                        prefs.edit().putStringSet("c${container.id}", prefs.getStringSet("c${container.id}", emptySet()).orEmpty() - setOf(component.name, "${component.name}_dll")).commit()
                        if (notes.isEmpty()) "Component removed. Original files and registry restored." else notes.joinToString("\n")
                    } else {
                        val error = OfflineComponentInstaller.install(context, container, component,
                            onProgress = { value -> scope.launch { progress = value.coerceIn(0f, 1f) } },
                            onStage = { value -> scope.launch { stage = value } })
                        check(error == null) { error.orEmpty() }
                        val now = prefs.getStringSet("c${container.id}", emptySet()).orEmpty()
                        prefs.edit().putStringSet("c${container.id}", now + component.name).commit()
                        "Component installed. Applies to every game in this container."
                    }
                }
                message = result
            } catch (e: Exception) { message = e.message ?: "Component operation failed" }
            finally { ContainerSessionGate.edited(container.id); refreshInstalled(); busy = null }
        }
    }
    androidx.activity.compose.BackHandler { if (busy == null) close() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Windows Components") }, navigationIcon = {
            TextButton(onClick = close, enabled = busy == null) { Text("Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${container.name} · shared by all games in this container", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it }, label = { Text("Search components") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (busy != null) {
                Text(stage, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            if (loading) CircularProgressIndicator()
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(catalog.filter { !it.name.endsWith("_dll") && (it.name.contains(query, true) || it.description.contains(query, true)) }
                    .sortedWith(compareBy<Component> { it.name !in installed }.thenBy { it.name }), key = { it.name }) { component ->
                    val have = component.name in installed
                    val supported = OfflineComponentInstaller.canInstall(component)
                    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(component.name, style = MaterialTheme.typography.titleSmall)
                            Text(component.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (have) "Installed" else if (supported) "Installs without Wine" else "Requires a Wine installer",
                                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                                if (have) TextButton(onClick = { removing = component }, enabled = busy == null) { Text("Remove") }
                                TextButton(onClick = { operate(component, false) }, enabled = supported && busy == null) {
                                    Text(if (have) "Reinstall" else "Install")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("Windows Components") },
        text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }) }
    removing?.let { component -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${component.name}?") },
        text = { Text("Restore the files and registry recorded for this component in ${container.name}. Every game in this container is affected.") },
        confirmButton = { TextButton(onClick = { removing = null; operate(component, true) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
}
