package com.winlator.cmod.ui.settings

import com.winlator.cmod.ui.uiStr
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinZTheme

data class PresetEditorVariable(
    val name: String,
    val value: String,
    val values: List<String>,
    val toggle: Boolean,
    val editable: Boolean,
    val help: String?
)

fun interface PresetEditorSaveListener {
    fun onSave(name: String, values: Map<String, String>)
}

object PresetEditorComposeDialog {
    @JvmStatic
    fun create(
        context: Context,
        title: String,
        initialName: String,
        readOnly: Boolean,
        variables: List<PresetEditorVariable>,
        listener: PresetEditorSaveListener
    ): Dialog {
        val dialog: Dialog = ComponentDialog(context, R.style.ContentDialog_Dark)
        val composeView = ComposeView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setContent {
                WinZTheme {
                    PresetEditorScreen(
                        title = title,
                        initialName = initialName,
                        readOnly = readOnly,
                        variables = variables,
                        onCancel = dialog::dismiss,
                        onSave = { name, values ->
                            listener.onSave(name, values)
                            dialog.dismiss()
                        }
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.76f }
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        return dialog
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetEditorScreen(
    title: String,
    initialName: String,
    readOnly: Boolean,
    variables: List<PresetEditorVariable>,
    onCancel: () -> Unit,
    onSave: (String, Map<String, String>) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val values = remember(variables) {
        mutableStateMapOf<String, String>().apply {
            variables.forEach { put(it.name, it.value) }
        }
    }
    var pickerIndex by remember { mutableStateOf<Int?>(null) }
    var helpText by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.12f)).padding(18.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).heightIn(max = 760.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (readOnly) uiStr(R.string.ui_bundled_preset_read_only) else uiStr(R.string.ui_edit_the_preset_name_and_environment_variables),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = !readOnly,
                    singleLine = true,
                    label = { Text(uiStr(R.string.preset)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    uiStr(R.string.ui_environment_variables),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 18.dp, bottom = 7.dp)
                )
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
                    ) {
                        itemsIndexed(variables, key = { _, item -> item.name }) { index, variable ->
                            PresetVariableRow(
                                variable = variable,
                                value = values[variable.name] ?: variable.value,
                                readOnly = readOnly,
                                onValueChange = { values[variable.name] = it },
                                onChoose = { pickerIndex = index },
                                onHelp = { variable.help?.let { helpText = it } }
                            )
                            if (index != variables.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(uiStr(R.string.cancel)) }
                    Button(
                        onClick = {
                            val cleanName = name.trim().replace(Regex("[,|]+"), "")
                            if (cleanName.isNotEmpty()) onSave(cleanName, values.toMap())
                        },
                        enabled = !readOnly && name.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text(uiStr(R.string.save)) }
                }
            }
        }
    }

    pickerIndex?.let { index ->
        val variable = variables.getOrNull(index)
        if (variable != null) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { pickerIndex = null },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 20.dp)) {
                    Text(
                        variable.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    variable.values.forEach { option ->
                        Surface(
                            onClick = {
                                values[variable.name] = option
                                pickerIndex = null
                            },
                            color = androidx.compose.ui.graphics.Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(option, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                if (values[variable.name] == option) {
                                    Icon(Icons.Outlined.Check, null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    helpText?.let { message ->
        AlertDialog(
            onDismissRequest = { helpText = null },
            confirmButton = {
                TextButton(onClick = { helpText = null }) { Text(uiStr(R.string.ok)) }
            },
            text = { Text(message) }
        )
    }
}

@Composable
private fun PresetVariableRow(
    variable: PresetEditorVariable,
    value: String,
    readOnly: Boolean,
    onValueChange: (String) -> Unit,
    onChoose: () -> Unit,
    onHelp: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                variable.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!variable.help.isNullOrBlank()) {
                IconButton(onClick = onHelp, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.HelpOutline,
                        contentDescription = uiStr(R.string.ui_help),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (variable.toggle) {
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = value == "1",
                    enabled = !readOnly,
                    onCheckedChange = { onValueChange(if (it) "1" else "0") }
                )
            }
        }
        if (!variable.toggle) {
            Spacer(Modifier.size(6.dp))
            if (variable.editable) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = !readOnly,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Surface(
                    onClick = onChoose,
                    enabled = !readOnly,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(9.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
