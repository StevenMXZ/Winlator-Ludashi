package com.winlator.cmod.ui.inputcontrols

import com.winlator.cmod.ui.uiStr
import android.content.Context
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.ui.LandscapeMainNavigation
import com.winlator.cmod.ui.theme.WinZTheme
import kotlin.math.roundToInt

@Immutable
data class InputProfileItem(val id: Int, val name: String)

@Immutable
data class InputControllerItem(
    val index: Int,
    val name: String,
    val bindings: Int,
    val connected: Boolean
)

@Immutable
data class InputControlsModel(
    val profiles: List<InputProfileItem>,
    val selectedProfileId: Int,
    val opacityPercent: Int,
    val controllers: List<InputControllerItem>
)

@Stable
interface InputControlsCallbacks {
    fun onProfileSelected(profileId: Int)
    fun onOpacityChanged(percent: Int)
    fun onAddProfile()
    fun onEditProfile()
    fun onDuplicateProfile()
    fun onRemoveProfile()
    fun onImportProfile()
    fun onExportProfile()
    fun onOpenEditor()
    fun onOpenController(index: Int)
    fun onRemoveController(index: Int)
}

object InputControlsComposeHost {
    @JvmStatic
    fun create(context: Context, model: InputControlsModel, callbacks: InputControlsCallbacks): ComposeView {
        val modelState = mutableStateOf(model)
        return ComposeView(context).apply {
            tag = modelState
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WinZTheme { InputControlsScreen(modelState.value, callbacks) } }
        }
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun update(view: View?, model: InputControlsModel) {
        (view?.tag as? MutableState<InputControlsModel>)?.value = model
    }
}

@Composable
private fun InputControlsScreen(model: InputControlsModel, callbacks: InputControlsCallbacks) {
    val selectedName = model.profiles.firstOrNull { it.id == model.selectedProfileId }?.name ?: "-- Select Profile --"
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val activity = LocalContext.current as? MainActivity

    DisposableEffect(activity, landscape) {
        if (landscape) {
            activity?.setBottomNavigationVisible(false)
            activity?.setMainToolbarVisible(false)
        }
        onDispose {
            if (landscape) {
                activity?.setBottomNavigationVisible(true)
                activity?.setMainToolbarVisible(true)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (landscape) LandscapeMainNavigation(activity, R.id.main_menu_input_controls, uiStr(R.string.input_controls))
        if (landscape) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item { ProfileSection(model, selectedName, callbacks) }
                    item { OpacityCard(model.opacityPercent, callbacks::onOpacityChanged) }
                    item { TransferActions(callbacks) }
                    item { EditorButton(callbacks) }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text(
                            uiStr(R.string.ui_external_controllers),
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (model.controllers.isEmpty()) item { EmptyControllers() }
                    else items(model.controllers, key = { "controller-${it.index}-${it.name}" }) { ControllerCard(it, callbacks) }
                }
            }
        } else {
            PortraitContent(model, selectedName, callbacks)
        }
    }
}

@Composable
private fun PortraitContent(model: InputControlsModel, selectedName: String, callbacks: InputControlsCallbacks) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { ProfileSection(model, selectedName, callbacks) }
        item { OpacityCard(model.opacityPercent, callbacks::onOpacityChanged) }
        item { TransferActions(callbacks) }
        item { EditorButton(callbacks) }
        item {
            Text(
                uiStr(R.string.ui_external_controllers),
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (model.controllers.isEmpty()) item { EmptyControllers() }
        else items(model.controllers, key = { "controller-${it.index}-${it.name}" }) { ControllerCard(it, callbacks) }
    }
}

@Composable
private fun ProfileSection(model: InputControlsModel, selectedName: String, callbacks: InputControlsCallbacks) {
    SettingsCard(title = uiStr(R.string.profile)) {
        ProfilePicker(model, selectedName, callbacks)
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            RoundAction(Icons.Outlined.Add, uiStr(R.string.ui_add_profile), callbacks::onAddProfile)
            Spacer(Modifier.width(10.dp))
            RoundAction(Icons.Outlined.Edit, uiStr(R.string.ui_edit_profile), callbacks::onEditProfile)
            Spacer(Modifier.width(10.dp))
            RoundAction(Icons.Outlined.ContentCopy, uiStr(R.string.ui_duplicate_profile), callbacks::onDuplicateProfile)
            Spacer(Modifier.width(10.dp))
            RoundAction(Icons.Outlined.Delete, uiStr(R.string.ui_remove_profile), callbacks::onRemoveProfile)
        }
    }
}

@Composable
private fun TransferActions(callbacks: InputControlsCallbacks) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = callbacks::onImportProfile,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text(uiStr(R.string.ui_import)) }
        OutlinedButton(
            onClick = callbacks::onExportProfile,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) { Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(8.dp)); Text(uiStr(R.string.export)) }
    }
}

@Composable
private fun EditorButton(callbacks: InputControlsCallbacks) {
    Button(
        onClick = callbacks::onOpenEditor,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
    ) {
        Icon(Icons.Outlined.SportsEsports, null)
        Spacer(Modifier.width(10.dp))
        Text(uiStr(R.string.controls_editor), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ProfilePicker(model: InputControlsModel, selectedName: String, callbacks: InputControlsCallbacks) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selectedName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth(0.82f)) {
            DropdownMenuItem(text = { Text(uiStr(R.string.ui_select_profile)) }, onClick = { expanded = false; callbacks.onProfileSelected(0) })
            model.profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { expanded = false; callbacks.onProfileSelected(profile.id) }
                )
            }
        }
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        IconButton(onClick = onClick) { Icon(icon, description, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun OpacityCard(initialPercent: Int, onOpacityChanged: (Int) -> Unit) {
    var opacity by remember(initialPercent) { mutableFloatStateOf(initialPercent.toFloat()) }
    SettingsCard(title = uiStr(R.string.overlay_opacity)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = opacity,
                onValueChange = { opacity = (it / 5f).roundToInt() * 5f },
                onValueChangeFinished = { onOpacityChanged(opacity.roundToInt()) },
                modifier = Modifier.weight(1f), valueRange = 0f..100f, steps = 19
            )
            Spacer(Modifier.width(14.dp))
            Text("${opacity.roundToInt()}%", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyControllers() {
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Gamepad, null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text(uiStr(R.string.ui_no_controllers_connected), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ControllerCard(controller: InputControllerItem, callbacks: InputControlsCallbacks) {
    Surface(
        onClick = { callbacks.onOpenController(controller.index) },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.size(42.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Gamepad, null, tint = if (controller.connected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(controller.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(uiStr(R.string.ui_1_s_bindings, controller.bindings), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (controller.bindings > 0) IconButton(onClick = { callbacks.onRemoveController(controller.index) }) { Icon(Icons.Outlined.Delete, uiStr(R.string.ui_remove_controller)) }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
    }
}
