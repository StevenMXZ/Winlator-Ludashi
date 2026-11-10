package com.winlator.cmod.ui.inputcontrols

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import com.winlator.cmod.R
import com.winlator.cmod.inputcontrols.Binding
import com.winlator.cmod.inputcontrols.ControlElement
import com.winlator.cmod.inputcontrols.ControlsProfile
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.widget.InputControlsView
import java.io.File
import kotlin.math.roundToInt

/**
 * Compose owns only the editor chrome. The existing InputControlsView and profile remain the
 * sole source of truth for editing positions, drawing controls, and saving in-game bindings.
 */
object ControlsEditorComposeHost {
    interface Callbacks {
        fun onBrowseIcon(element: ControlElement, overrideId: Int)
    }

    @JvmStatic
    fun create(context: Context, profile: ControlsProfile, canvas: InputControlsView, callbacks: Callbacks): ComposeView {
        val revision = mutableIntStateOf(0)
        return ComposeView(context).apply {
            tag = revision
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WinZTheme { Editor(profile, canvas, callbacks, revision.intValue) } }
        }
    }

    @JvmStatic
    fun refresh(view: View?) {
        (view?.tag as? MutableIntState)?.let { it.intValue++ }
    }
}

private val palette = intArrayOf(
    0, 0xffffffff.toInt(), 0xffdddddd.toInt(), 0xffaaaaaa.toInt(), 0xff777777.toInt(), 0xff444444.toInt(), 0xff222222.toInt(), 0xff000000.toInt(),
    0xffff3b30.toInt(), 0xffff6961.toInt(), 0xffff453a.toInt(), 0xffcc0000.toInt(), 0xff800000.toInt(), 0xff4d0000.toInt(), 0xffff8080.toInt(), 0xffffb3b3.toInt(),
    0xffff9500.toInt(), 0xffff6000.toInt(), 0xfffe9f0d.toInt(), 0xffffcc00.toInt(), 0xffffd60a.toInt(), 0xffffea00.toInt(), 0xffffb347.toInt(), 0xffffdca5.toInt(),
    0xff34c759.toInt(), 0xff30d158.toInt(), 0xff4cd964.toInt(), 0xff00b050.toInt(), 0xff2e8b57.toInt(), 0xff006400.toInt(), 0xffa8e6cf.toInt(), 0xffd4edda.toInt(),
    0xff5ac8fa.toInt(), 0xff32ade6.toInt(), 0xff00b4d8.toInt(), 0xff0096c7.toInt(), 0xff00758a.toInt(), 0xff004c5a.toInt(), 0xffb2ebf2.toInt(), 0xffe0f7fa.toInt(),
    0xff007aff.toInt(), 0xff0a84ff.toInt(), 0xff2184ff.toInt(), 0xff1a56db.toInt(), 0xff003f8f.toInt(), 0xff001a66.toInt(), 0xffbed6f8.toInt(), 0xffdce9ff.toInt(),
    0xffaf52de.toInt(), 0xffbf5af2.toInt(), 0xff9b59b6.toInt(), 0xff6e3fa3.toInt(), 0xff4a0e8f.toInt(), 0xff2d0066.toInt(), 0xffd7b4f3.toInt(), 0xffede0f8.toInt(),
    0xffff2d92.toInt(), 0xffff375f.toInt(), 0xffff6ab0.toInt(), 0xffe91e8c.toInt(), 0xffad1457.toInt(), 0xff6a0032.toInt(), 0xffffb3d9.toInt(), 0xffffdcef.toInt()
)

@Composable
private fun Editor(profile: ControlsProfile, canvas: InputControlsView, callbacks: ControlsEditorComposeHost.Callbacks, revision: Int) {
    var selected by remember { mutableStateOf<ControlElement?>(null) }
    var colors by remember { mutableStateOf(false) }
    var localRevision by remember { mutableIntStateOf(0) }
    val touchRevision: () -> Unit = {
        profile.save()
        canvas.invalidate()
        localRevision++
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { canvas.apply { setOnEditBackgroundTapListener { colors = false; selected = null } } }, modifier = Modifier.fillMaxSize())
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.profile), style = MaterialTheme.typography.labelSmall)
                    Text(profile.name, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
                IconButton(onClick = { canvas.addElement() }) { Icon(Icons.Outlined.Add, stringResource(R.string.add)) }
                IconButton(onClick = { canvas.removeElement() }) { Icon(Icons.Outlined.Delete, stringResource(R.string.remove)) }
                IconButton(onClick = { selected = canvas.selectedElement }) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings)) }
                IconButton(onClick = { colors = true }) { Icon(Icons.Outlined.Palette, "Scheme color") }
            }
        }
        if (colors) {
            EditorPanel("Scheme Color", { profile.save(); colors = false }) {
                Text("Applies to every control that doesn't have its own custom color", style = MaterialTheme.typography.bodySmall)
                ColorGrid(profile.themeColor) { color -> profile.themeColor = color; touchRevision() }
            }
        }
        selected?.let { element ->
            // The canvas remains interactive while the settings panel is open.
            key(element, revision) {
                EditorPanel(stringResource(R.string.settings), { profile.save(); selected = null }) {
                    val stateRevision = localRevision
                    ElementFields(element, profile, canvas, callbacks, stateRevision, touchRevision)
                }
            }
        }
    }
}

@Composable
private fun BoxScope.EditorPanel(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val panelWidth = if (landscape) minOf(330f, configuration.screenWidthDp * 0.44f).dp
        else (configuration.screenWidthDp - 24).dp
    Surface(
        modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp).width(panelWidth)
            .heightIn(max = (configuration.screenHeightDp - 24).dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss) { Text("Save") }
            }
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = (configuration.screenHeightDp - 104).coerceAtLeast(160).dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) { content() }
        }
    }
}

@Composable
private fun ColorGrid(selected: Int, onSelected: (Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        modifier = Modifier.width(224.dp).height(224.dp),
        userScrollEnabled = false
    ) {
        items(palette.size) { index ->
            val color = palette[index]
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Surface(
                    onClick = { onSelected(color) },
                    modifier = Modifier.size(24.dp),
                    shape = CircleShape,
                    color = if (color == 0) Color.White else Color(color),
                    border = if (selected == color) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                ) {}
            }
        }
    }
}

@Composable
private fun <T> Choice(label: String, selected: T, options: List<T>, text: (T) -> String, onSelected: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(text(selected), modifier = Modifier.weight(1f))
            Text("▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(text(option)) }, onClick = { expanded = false; onSelected(option) })
            }
        }
    }
}

@Composable
private fun Percent(label: String, value: Float, step: Int, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    var progress by remember(value) { mutableStateOf(value) }
    Column {
        Text("$label: ${(progress * 100).roundToInt()}%")
        Slider(
            value = progress,
            onValueChange = {
                val rounded = if (step == 1) (it * 100).roundToInt() else ((it * 100 / step).roundToInt() * step)
                progress = rounded.coerceIn(0, 100) / 100f
                onChange(progress)
            },
            onValueChangeFinished = onFinished
        )
    }
}

@Composable
private fun ElementFields(
    element: ControlElement,
    profile: ControlsProfile,
    canvas: InputControlsView,
    callbacks: ControlsEditorComposeHost.Callbacks,
    revision: Int,
    save: () -> Unit
) {
    // Reading this state makes external icon picker changes and local selections recompose.
    @Suppress("UNUSED_VARIABLE") val update = revision
    val type = element.type
    Choice(stringResource(R.string.type), type, ControlElement.Type.values().toList(), { it.name.replace('_', '-') }) {
        // setType resets all bindings. Initial rendering and reselecting the same type must not call it (#642).
        if (it != element.type) { element.type = it; save() }
    }
    if (type == ControlElement.Type.BUTTON) {
        Choice(stringResource(R.string.shape), element.shape, ControlElement.Shape.values().toList(), { it.name.replace('_', ' ') }) {
            if (it != element.shape) { element.shape = it; save() }
        }
    }
    Percent(stringResource(R.string.scale), element.scale, 5, { element.scale = it; canvas.invalidate() }, save)
    if (type == ControlElement.Type.STICK || type == ControlElement.Type.DYNAMIC_STICK) {
        var deadZone by remember(element) { mutableStateOf(element.deadZone) }
        Column {
            Text("Dead zone: ${(deadZone * 100).roundToInt()}%")
            Slider(
                value = deadZone,
                onValueChange = {
                    deadZone = (it * 100).roundToInt() / 100f
                    element.deadZone = deadZone
                },
                onValueChangeFinished = save,
                valueRange = 0f..0.5f
            )
        }
    }
    if (type == ControlElement.Type.DYNAMIC_STICK) {
        RatioSlider("Touch area width", element.areaWidthRatio, 0.15f, 1f,
            { element.areaWidthRatio = it; canvas.invalidate() }, save)
        RatioSlider("Touch area height", element.areaHeightRatio, 0.15f, 1f,
            { element.areaHeightRatio = it; canvas.invalidate() }, save)
        RatioSlider("Stick radius", element.stickRadiusRatio, 0.05f, 0.3f,
            { element.stickRadiusRatio = it; canvas.invalidate() }, save)
    }
    Percent("Opacity", element.opacity, 1, { element.opacity = it; canvas.invalidate() }, save)
    Text("Theme Color", style = MaterialTheme.typography.labelMedium)
    ColorGrid(element.customColor) { element.customColor = it; save() }
    if (type == ControlElement.Type.BUTTON) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.toggle_switch), modifier = Modifier.weight(1f))
            Switch(checked = element.isToggleSwitch, onCheckedChange = { element.isToggleSwitch = it; save() })
        }
        var customText by remember(element) { mutableStateOf(element.text.orEmpty()) }
        OutlinedTextField(
            value = customText,
            onValueChange = { customText = it; element.text = it.trim(); save() },
            label = { Text(stringResource(R.string.custom_text)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Text(stringResource(R.string.icon), style = MaterialTheme.typography.labelMedium)
        IconGallery(element, canvas, callbacks, revision, save)
    }
    if (type == ControlElement.Type.RANGE_BUTTON) {
        Choice(stringResource(R.string.range), element.range, ControlElement.Range.values().toList(), { it.name.replace('_', ' ') }) {
            if (it != element.range) { element.range = it; save() }
        }
        Choice(stringResource(R.string.orientation), element.orientation.toInt(), listOf(0, 1), { if (it == 0) "Horizontal" else "Vertical" }) {
            if (it != element.orientation.toInt()) { element.orientation = it.toByte(); save() }
        }
        Choice(stringResource(R.string.columns), element.bindingCount, (3..8).toList(), { it.toString() }) {
            if (it != element.bindingCount) { element.bindingCount = it; save() }
        }
    }
    val bindingLabels = when (type) {
        ControlElement.Type.BUTTON -> listOf(R.string.binding, R.string.binding_secondary)
        ControlElement.Type.D_PAD, ControlElement.Type.STICK, ControlElement.Type.DYNAMIC_STICK, ControlElement.Type.TRACKPAD ->
            listOf(R.string.binding_up, R.string.binding_right, R.string.binding_down, R.string.binding_left)
        else -> emptyList()
    }
    bindingLabels.forEachIndexed { index, label ->
        key(type, index) { BindingField(stringResource(label), element, index, save) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Move mouse cursor while dragging", modifier = Modifier.weight(1f))
        Switch(checked = element.isMouseMoveMode, onCheckedChange = { element.isMouseMoveMode = it; save() })
    }
}

@Composable
private fun RatioSlider(
    label: String, value: Float, min: Float, max: Float,
    onChange: (Float) -> Unit, onFinished: () -> Unit
) {
    var progress by remember(value) { mutableStateOf(value) }
    Column {
        Text("$label: ${(progress * 100).roundToInt()}%")
        Slider(
            value = progress,
            onValueChange = {
                progress = ((it * 100).roundToInt() / 100f).coerceIn(min, max)
                onChange(progress)
            },
            onValueChangeFinished = onFinished,
            valueRange = min..max
        )
    }
}

@Composable
private fun BindingField(label: String, element: ControlElement, index: Int, save: () -> Unit) {
    val current = element.getBindingAt(index)
    var family by remember(element, index) {
        mutableIntStateOf(if (current.isMouse) 1 else if (current.isGamepad) 2 else 0)
    }
    val options = when (family) {
        1 -> Binding.mouseBindingValues().toList()
        2 -> Binding.gamepadBindingValues().toList()
        else -> Binding.keyboardBindingValues().toList()
    }
    Text(label, style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            Choice("Input", family, listOf(0, 1, 2), { listOf("Keyboard", "Mouse", "Gamepad")[it] }) { family = it }
        }
        Box(Modifier.weight(1f)) {
            Choice("Binding", current, options, { it.toString() }) {
                if (it != element.getBindingAt(index)) { element.setBindingAt(index, it); save() }
            }
        }
    }
}

private data class IconOption(val id: Int, val path: String?, val bitmap: ImageBitmap)

private fun loadIcons(context: Context): List<IconOption> {
    val iconsDir = File(Environment.getExternalStorageDirectory(), "winlator/custom_icons")
    val custom = iconsDir.listFiles { _, name -> name.endsWith(".png", true) && !name.startsWith("override_") }
        ?.sortedBy { it.name }?.mapNotNull { file ->
            decodeIcon(file.absolutePath)?.let { IconOption(0, file.absolutePath, it.asImageBitmap()) }
        }.orEmpty()
    val builtin = context.assets.list("inputcontrols/icons/").orEmpty()
        .mapNotNull { it.substringBeforeLast('.').toIntOrNull() }.sorted().mapNotNull { id ->
            val override = File(iconsDir, "override_$id.png")
            val bitmap = if (override.exists()) decodeIcon(override.absolutePath) else null
            val result = bitmap ?: runCatching {
                context.assets.open("inputcontrols/icons/$id.png").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            result?.let { IconOption(id, null, it.asImageBitmap()) }
        }
    return custom + builtin
}

private fun decodeIcon(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options()
    while (bounds.outWidth / options.inSampleSize > 256 || bounds.outHeight / options.inSampleSize > 256) {
        options.inSampleSize *= 2
    }
    return BitmapFactory.decodeFile(path, options)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconGallery(
    element: ControlElement,
    canvas: InputControlsView,
    callbacks: ControlsEditorComposeHost.Callbacks,
    revision: Int,
    save: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val icons = remember(context, revision) { loadIcons(context) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { callbacks.onBrowseIcon(element, -1) }) { Text("Browse…") }
        if (element.customIconPath != null) {
            OutlinedButton(onClick = { element.customIconPath = null; canvas.invalidateIconCache(); save() }) { Text("Remove") }
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(icons) { option ->
            val selected = if (option.path != null) option.path == element.customIconPath
                else element.customIconPath == null && option.id == element.iconId.toInt()
            Surface(
                modifier = Modifier.size(44.dp).combinedClickable(
                    onClick = {
                        if (option.path != null) {
                            element.customIconPath = option.path
                            element.setIconId(0)
                        } else {
                            element.customIconPath = null
                            element.setIconId(option.id)
                        }
                        canvas.invalidateIconCache()
                        save()
                    },
                    onLongClick = { if (option.path == null) callbacks.onBrowseIcon(element, option.id) }
                ),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
            ) {
                Image(
                    bitmap = option.bitmap,
                    contentDescription = "Icon ${option.path ?: option.id}",
                    modifier = Modifier.padding(4.dp),
                    colorFilter = if (option.path == null) ColorFilter.tint(Color(0xff2184ff)) else null
                )
            }
        }
    }
}
