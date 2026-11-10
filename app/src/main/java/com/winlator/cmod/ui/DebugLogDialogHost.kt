package com.winlator.cmod.ui

import com.winlator.cmod.ui.theme.DialogScrimColor
import androidx.activity.compose.BackHandler

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor
import com.winlator.cmod.ui.theme.dividerColor

// Backing store for DebugDialog's live log stream. append() only grows the list when not
// paused (matches the old LogView.append/DebugDialog.paused gating) — the file write in
// DebugDialog.call() always happens regardless of this flag, same as before.
class DebugLogState {
    val lines = mutableStateListOf<String>()
    var paused: Boolean by mutableStateOf(false)

    fun append(line: String) {
        if (paused) return
        lines.add(line)
        if (lines.size > 2000) lines.removeRange(0, lines.size - 2000)
    }

    fun clear() {
        lines.clear()
    }
}

// Themed replacement for the old ContentDialog-based debug panel (debug_dialog.xml +
// debug_toolbar.xml) — a full-screen scrim + large card, added straight onto the activity's
// content root like the app's other Themed*Host overlays (ThemedAlertHost, ThemedProgressHost,
// ThemedLoadingOverlayHost), rather than a native android.app.Dialog window.
object DebugLogDialogHost {
    @JvmStatic
    fun show(activity: Activity, state: DebugLogState, onDismissed: Runnable): View {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        lateinit var composeView: ComposeView
        composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZOverlayTheme {
                    val dismiss = {
                        (composeView.parent as? ViewGroup)?.removeView(composeView)
                        onDismissed.run()
                    }
                    // System back closes this overlay instead of reaching the screen behind it
                    // (it lives in the activity's own view tree, not a Dialog window).
                    BackHandler { dismiss() }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(DialogScrimColor)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = dismiss
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .fillMaxHeight(0.82f)
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
                            shape = WinZShapes.ExtraLarge,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, hairlineColor())
                        ) {
                            DebugLogContent(state, onClose = dismiss)
                        }
                    }
                }
            }
        }
        root.addView(
            composeView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        return composeView
    }
}

@Composable
private fun DebugLogContent(state: DebugLogState, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.BugReport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.logs),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { state.clear() }) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.ui_clear_logs), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { state.paused = !state.paused }) {
                Icon(
                    if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    contentDescription = if (state.paused) stringResource(R.string.ui_resume) else stringResource(R.string.ui_pause),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.ui_close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = dividerColor())
        Spacer(Modifier.height(8.dp))

        if (state.lines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.no_items_to_display),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Auto-follows new lines while live, same as a typical log tail; freezes in
            // place while paused so the user can read back through older entries — the
            // old LogView needed a manual touch-drag-pauses-ingestion hack for this same
            // reason, which a LazyColumn's own scroll position makes unnecessary here.
            val listState = rememberLazyListState()
            LaunchedEffect(state.lines.size, state.paused) {
                if (!state.paused && state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.size - 1)
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(state.lines) { index, line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (index % 2 != 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else Color.Transparent)
                            .padding(vertical = 3.dp, horizontal = 4.dp)
                    )
                }
            }
        }
    }
}
