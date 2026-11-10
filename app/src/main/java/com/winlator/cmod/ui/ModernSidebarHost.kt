package com.winlator.cmod.ui

import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Spinner
import android.widget.TextView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.winlator.cmod.R
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.ui.theme.*
import com.winlator.cmod.widget.SeekBar as LegacySlider

// WinLite's unified rail/panel presentation, adapted to Ludashi's existing rendering,
// HUD and input bindings. The native binding tree stays attached but hidden, so backend
// listeners and their autosave/renderer-specific visibility remain authoritative.
// Runtime diagnostics use a bounded background probe; renderer bindings stay authoritative.
class ModernSidebarController internal constructor(internal val activity: XServerDisplayActivity, private val root: ViewGroup) {
    internal var selected by mutableIntStateOf(R.id.LLSubFPS)
    internal var revision by mutableIntStateOf(0)
    internal var paused by mutableStateOf(false)
    internal val panelRoots = listOf(R.id.LLSubGraphics, R.id.LLSubScreen, R.id.LLSubInput, R.id.LLSubFPS, R.id.LLSubTaskManager)
        .associateWith { activity.findViewById<View>(it) }
    // Coalesce control updates into one UI snapshot after the legacy widgets
    // have applied their dependent visibility/selection changes.
    private var refreshPosted = false
    internal fun refresh() {
        if (refreshPosted) return
        refreshPosted = true
        root.post {
            refreshPosted = false
            revision++
        }
    }
    internal fun select(panel: Int) {
        selected = panel
        val button = when (panel) {
            R.id.LLSubGraphics -> R.id.BTItemGraphics
            R.id.LLSubScreen -> R.id.BTItemScreen
            R.id.LLSubInput -> R.id.BTItemInput
            R.id.LLSubTaskManager -> R.id.BTItemTaskManager
            else -> R.id.BTItemFPS
        }
        activity.findViewById<View>(button)?.performClick()
        refresh()
    }
    fun onRuntimeStatusChanged() { revision++ }
    fun onLocaleChanged() { revision++ }
    fun onOpened(isPaused: Boolean) { paused = isPaused; select(selected); activity.requestSidebarRuntimeStatus() }
    internal fun pause() { activity.findViewById<View>(R.id.BTItemPause)?.performClick(); paused = !paused }
    internal fun exit() { activity.findViewById<View>(R.id.BTItemExit)?.performClick() }
}

object ModernSidebarHost {
    @JvmStatic fun attach(activity: XServerDisplayActivity): ModernSidebarController? {
        val root = activity.findViewById<ViewGroup>(R.id.IngameSidebarRoot) ?: return null
        val controller = ModernSidebarController(activity, root)
        // Hide the complete old presentation, rather than toggling individual descendants.
        // All its IDs/listeners remain available to the Activity and binding helpers.
        for (i in 0 until root.childCount) root.getChildAt(i).visibility = View.GONE
        val host = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WinZOverlayTheme { Sidebar(controller) } }
        }
        root.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return controller
    }
}

private data class Section(val id: Int, val icon: Int, val label: Int)
private val sections = listOf(
    Section(R.id.LLSubGraphics, R.drawable.ic_sidebar_gpu, R.string.ui_rendering),
    Section(R.id.LLSubScreen, R.drawable.ic_sidebar_effects, R.string.ui_screen),
    Section(R.id.LLSubInput, R.drawable.icon_mouse, R.string.ui_input),
    Section(R.id.LLSubFPS, R.drawable.ic_sidebar_performance, R.string.ui_hud),
    Section(R.id.LLSubTaskManager, R.drawable.ic_sidebar_task_manager, R.string.ui_task_manager)
)

@Composable
private fun Sidebar(state: ModernSidebarController) {
    Surface(Modifier.fillMaxSize().padding(start = 6.dp, top = 10.dp, end = 10.dp, bottom = 10.dp).pointerInput(Unit) { },
        shape = WinZShapes.ExtraLarge, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, hairlineColor()), shadowElevation = 8.dp) {
        Row(Modifier.fillMaxSize()) {
            // Two independent tap buttons are always reachable; on short screens the rail scrolls.
            BoxWithConstraints(Modifier.width(58.dp).fillMaxHeight()) {
            val cellSize = minOf(44.dp, (maxHeight - 45.dp) / 7).coerceAtLeast(36.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 5.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sections.forEach { section ->
                    RailButton(state.selected == section.id, { state.select(section.id) }, cellSize) {
                        Icon(painterResource(section.icon), stringResource(section.label), Modifier.size(23.dp))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = dividerColor())
                RailButton(false, state::pause, cellSize) {
                    Icon(if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, stringResource(if (state.paused) R.string.ui_resume else R.string.ui_pause))
                }
                RailButton(false, state::exit, cellSize) { Icon(Icons.Outlined.PowerSettingsNew, stringResource(R.string.ui_exit), tint = MaterialTheme.colorScheme.error) }
            }
            }
            VerticalDivider(color = dividerColor())
            // Compose only the selected panel. Previously all five panels were
            // laid out simultaneously behind transparent graphics layers, even
            // though the legacy sidebar rendered just one visible ViewGroup.
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val panel = state.selected
                val section = sections.firstOrNull { it.id == panel } ?: sections[3]
                val source = state.panelRoots[panel]
                Column(
                    Modifier.fillMaxSize()
                        .then(if (panel == R.id.LLSubTaskManager) Modifier
                              else Modifier.verticalScroll(rememberScrollState()))
                        .padding(if (panel == R.id.LLSubFPS) 10.dp else 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val revision = state.revision
                    if (panel == R.id.LLSubTaskManager && source != null) {
                        TaskManagerPanel(source, true)
                    } else {
                        Text(stringResource(section.label),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold)
                        if (panel == R.id.LLSubGraphics)
                            RuntimeStatusCard(state.activity.sidebarRuntimeStatusRows)
                        if (panel == R.id.LLSubScreen) FullscreenModeSelector(state)
                        if (source is ViewGroup)
                            LegacyFields(source, revision, true, state::refresh,
                                stringResource(section.label),
                                if (panel == R.id.LLSubInput) state else null)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExclusiveInputRow(state: ModernSidebarController) {
    val activity = state.activity
    @Suppress("UNUSED_VARIABLE") val revision = state.revision
    Surface(shape = WinZShapes.Medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(uiStr(R.string.ui_exclusive_input), style = MaterialTheme.typography.bodyMedium)
                if (activity.isExclusiveInputBusy) Text(uiStr(R.string.ui_applying), style = MaterialTheme.typography.bodySmall)
            }
            Switch(activity.isExclusiveInputEnabled, { activity.changeExclusiveInput(it, state::refresh) },
                enabled = activity.canChangeExclusiveInput())
        }
    }
}

@Composable
private fun InputApiRow(state: ModernSidebarController) {
    val activity = state.activity
    @Suppress("UNUSED_VARIABLE") val revision = state.revision
    val canEdit = activity.canChangeExclusiveInput() && activity.isExclusiveInputEnabled
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(shape = WinZShapes.Medium, color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(uiStr(R.string.ui_enable_xinput), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(activity.isSidebarXInputEnabled, { enabled ->
                    state.activity.changeSidebarInputApis(enabled, if (enabled) false else activity.isSidebarDInputEnabled, state::refresh)
                }, enabled = canEdit)
            }
        }
        Surface(shape = WinZShapes.Medium, color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(uiStr(R.string.ui_enable_dinput), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(activity.isSidebarDInputEnabled, { enabled ->
                    state.activity.changeSidebarInputApis(if (enabled) false else activity.isSidebarXInputEnabled, enabled, state::refresh)
                }, enabled = canEdit)
            }
        }
    }
}

@Composable
private fun RailButton(selected: Boolean, click: () -> Unit, cellSize: androidx.compose.ui.unit.Dp, icon: @Composable () -> Unit) {
    val fill by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, WinZMotion.standard(150), label = "railSelection")
    Surface(click, Modifier.size(cellSize), shape = WinZShapes.Medium,
        color = fill,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
internal fun TaskManagerPanel(view: View, active: Boolean) {
    NativeBlock(view, active, Modifier.fillMaxSize())
}

@Composable
private fun NativeBlock(view: View, active: Boolean, modifier: Modifier = Modifier.fillMaxWidth()) {
    AndroidView(factory = {
        (view.parent as? ViewGroup)?.let { parent ->
            val index = parent.indexOfChild(view)
            val marker = View(view.context).apply { tag = view }
            parent.removeView(view)
            parent.addView(marker, index, ViewGroup.LayoutParams(0, 0))
        }
        view.visibility = View.VISIBLE
        view
    }, update = {
        it.importantForAccessibility = if (active) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        (it as? ViewGroup)?.descendantFocusability = if (active) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (!active) it.clearFocus()
    }, modifier = modifier)
}

private fun labelOf(view: View): String {
    if (view is TextView && view.text.isNotBlank()) return view.text.toString()
    if (view is ViewGroup) for (i in 0 until view.childCount) {
        val text = labelOf(view.getChildAt(i))
        if (text.isNotBlank()) return text
    }
    return view.contentDescription?.toString().orEmpty()
}

@Composable
private fun LegacyFields(group: ViewGroup, revision: Int, active: Boolean, refresh: () -> Unit, heading: String,
                         sidebar: ModernSidebarController? = null) {
    // Accessing revision re-reads the existing bindings after an action, without reinitializing
    // the controls or triggering their listeners during composition.
    @Suppress("UNUSED_VARIABLE") val snapshot = revision
    for (i in 0 until group.childCount) {
        val view = group.getChildAt(i)
        if (view.visibility != View.VISIBLE) continue
        if (view is TextView && view.id == View.NO_ID &&
            listOf(heading, uiStr(R.string.ui_rendering), uiStr(R.string.ui_display_and_effects), uiStr(R.string.sidebar_controls), "HUD")
                .any { view.text.toString().trim().equals(it, ignoreCase = true) }) continue
        if (view.id == R.id.BTItemToggleFullscreen || view.id == R.id.BTItemSoftStretch) continue
        // Both fullscreen actions are represented by the three-way display-mode selector.
        when {
            view.tag == "winz-hud-options-v2" && view is ViewGroup -> HudCheckboxGrid(view, revision, refresh)
            view.tag is FpsLimiterControl -> NativeBlock(view.tag as View, active)
            view is FpsLimiterControl -> NativeBlock(view, active)
            view is CompoundButton -> {
                Surface(shape = WinZShapes.Medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(view.text.toString(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(view.isChecked, { view.isChecked = it; refresh() }, enabled = view.isEnabled)
                    }
                }
            }
            view is Spinner -> {
                val adapter = view.adapter
                if (adapter != null && adapter.count > 0) {
                    var expanded by remember(view) { mutableStateOf(false) }
                    val options = (0 until adapter.count).map { adapter.getItem(it).toString() }
                    Box {
                        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth(), shape = WinZShapes.Small, enabled = view.isEnabled) {
                            Text(options.getOrElse(view.selectedItemPosition) { options.first() }, Modifier.weight(1f))
                            Icon(Icons.Outlined.KeyboardArrowDown, null)
                        }
                        DropdownMenu(expanded, { expanded = false }) {
                            options.forEachIndexed { index, text ->
                                DropdownMenuItem(text = { Text(text) }, onClick = {
                                    expanded = false
                                    if (index != view.selectedItemPosition) {
                                        view.setSelection(index, false)
                                        // A hidden Spinner never lays itself out, so dispatch its real binding
                                        // explicitly. The binding remains responsible for saving and applying.
                                        view.onItemSelectedListener?.onItemSelected(view, null, index, adapter.getItemId(index))
                                        refresh()
                                    }
                                })
                            }
                        }
                    }
                }
            }
            view is LegacySlider -> {
                var value by remember(view, revision) { mutableFloatStateOf(view.value) }
                Slider(value.coerceIn(view.minValue, view.maxValue), {
                    value = it; view.value = it
                    view.onValueChangeListener?.onValueChangeListener(view, view.value, false)
                }, onValueChangeFinished = {
                    view.onValueChangeListener?.onValueChangeListener(view, view.value, true); refresh()
                }, valueRange = view.minValue..view.maxValue, enabled = view.isEnabled)
            }
            view is android.widget.SeekBar -> {
                var value by remember(view, revision) { mutableFloatStateOf(view.progress.toFloat()) }
                Slider(value, { value = it; view.progress = it.toInt() }, onValueChangeFinished = refresh,
                    valueRange = 0f..view.max.toFloat().coerceAtLeast(1f), enabled = view.isEnabled)
            }
            view.hasOnClickListeners() -> {
                val label = labelOf(view)
                if (label.isNotBlank()) OutlinedButton({ view.performClick(); refresh() }, Modifier.fillMaxWidth(), shape = WinZShapes.Medium, enabled = view.isEnabled) { Text(label) }
            }
            view is TextView -> if (view.text.isNotBlank()) Text(view.text.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            view is ViewGroup -> LegacyFields(view, revision, active, refresh, heading, sidebar)
        }
        if (view.id == R.id.SWEnableTouchscreenHaptics && sidebar != null) {
            ExclusiveInputRow(sidebar)
            InputApiRow(sidebar)
        }
    }
}

// Keep all display modes in one selector; native X++ currently supports Fit and Stretch.
@Composable
private fun FullscreenModeSelector(state: ModernSidebarController) {
    val mode = state.activity.sidebarDisplayMode
    val entries = listOf(
        0 to uiStr(R.string.sidebar_fit),
        1 to uiStr(R.string.sidebar_stretch),
        2 to uiStr(R.string.sidebar_soft_stretch)
    )
    Surface(shape = WinZShapes.Medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(uiStr(R.string.ui_display_mode), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                entries.forEach { (selectedMode, label) ->
                    FilterChip(
                        selected = mode == selectedMode,
                        onClick = { state.activity.setSidebarDisplayMode(selectedMode); state.refresh() },
                        enabled = selectedMode != 2 || state.activity.isSidebarSoftStretchSupported,
                        label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeStatusCard(rows: Array<out Array<out String>>) {
    Surface(shape = WinZShapes.Medium, color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, hairlineColor())) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(uiStr(R.string.ui_runtime_status), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row[0], Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(row[1], Modifier.weight(1.6f), style = MaterialTheme.typography.bodySmall,
                        color = when (row[2]) {
                            "active" -> MaterialTheme.colorScheme.primary
                            "warning" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        })
                }
            }
        }
    }
}

/** Compact metric grid: the full cell is the tap target, not an oversized checkbox. */
@Composable
private fun HudCheckboxGrid(group: ViewGroup, revision: Int, refresh: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val snapshot = revision
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            for (i in 0 until group.childCount) {
                val row = group.getChildAt(i)
                if (row is android.widget.Switch) {
                    HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 6.dp), color = dividerColor())
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .toggleable(row.isChecked, role = Role.Switch, onValueChange = {
                            row.isChecked = it; refresh()
                        }), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.text.toString(), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        // Scale only the track; the enclosing row remains an easy tap target.
                        Box(Modifier.width(42.dp), contentAlignment = Alignment.Center) {
                            Switch(row.isChecked, null, Modifier.graphicsLayer { scaleX = .75f; scaleY = .75f })
                        }
                    }
                } else {
                    val fields = if (row is ViewGroup)
                        (0 until row.childCount).map { row.getChildAt(it) }
                    else listOf(row)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fields.filterIsInstance<android.widget.CheckBox>().forEach { field ->
                            HudMetricCell(field, field.isChecked, Modifier.weight(1f), refresh)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HudMetricCell(field: android.widget.CheckBox, checked: Boolean, modifier: Modifier, refresh: () -> Unit) {
    Row(modifier.heightIn(min = 44.dp)
        .toggleable(checked, enabled = field.isEnabled, role = Role.Checkbox,
            onValueChange = { field.isChecked = it; refresh() }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Checkbox(checked, null, Modifier.size(20.dp), enabled = field.isEnabled,
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.onSurface,
                checkmarkColor = MaterialTheme.colorScheme.surface,
                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant))
        Text(field.text.toString(), Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
            softWrap = field.text.contains(' '), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
