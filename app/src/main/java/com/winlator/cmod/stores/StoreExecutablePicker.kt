package com.winlator.cmod.stores

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import java.io.File

@Composable internal fun StoreExecutablePicker(title: String, paths: List<String>, current: String, busy: Boolean, close: () -> Unit, select: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(current.ifBlank { StoreExecutables.default(paths, title) }) }
    val ordered = remember(paths, title) { StoreExecutables.ordered(paths, title) }
    val visible = remember(ordered, query, all, current) { ordered.filter { (all || !StoreExecutables.helper(it) || it == current) && it.contains(query, true) } }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(uiStr(R.string.gog_choose_executable)) }, text = {
        Column(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text(uiStr(R.string.store_search_executable)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(all, { all = it }); Text(uiStr(R.string.store_show_all_executables), style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(visible, key = { it }) { path ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { chosen = path }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(chosen == path, onClick = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(File(path).name, style = MaterialTheme.typography.titleSmall)
                            if (path != File(path).name) Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (visible.isEmpty()) item { Text(uiStr(R.string.store_no_executables)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { Button(enabled = !busy && chosen in paths, onClick = { select(chosen) }) { Text(uiStr(R.string.save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text(uiStr(R.string.cancel)) } })
}
