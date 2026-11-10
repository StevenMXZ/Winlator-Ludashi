package com.winlator.cmod.ui

import com.winlator.cmod.R
import androidx.compose.ui.res.stringResource
import com.winlator.cmod.ui.theme.DialogScrimColor
import com.winlator.cmod.ui.theme.WinZMotion
import androidx.activity.compose.BackHandler

import com.winlator.cmod.ui.theme.ThemedDialogTitle
import com.winlator.cmod.ui.theme.ThemedDialogScrollBody
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.ThemedDialogSurface
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.destructiveColor
import java.util.function.Consumer
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor
import com.winlator.cmod.ui.theme.dividerColor

// Native bridge so plain Java call sites (component/driver/runtime delete confirmations,
// "in use" warnings, etc.) get the exact same card look and button styling as every
// Compose-triggered dialog, instead of the stock (unthemed) android.app.AlertDialog.Builder.
//
// This is added directly into the Activity's own content view hierarchy (android.R.id.content)
// rather than a separate android.app.Dialog window: that hierarchy is already attached to the
// same window Compose already runs in elsewhere in this app (via fragments), so the ComposeView
// finds its lifecycle/viewmodel/saved-state owners for free — no extra dependency needed.
object ThemedAlertHost {
    // Number of overlays currently attached. Overlays live in the Activity's own view tree, so
    // an Activity that swallows touches in dispatchTouchEvent (e.g. XServerDisplayActivity while
    // the game is paused) must let them through while this is true. Main thread only.
    private var activeOverlays = 0

    @JvmStatic
    fun isShowing(): Boolean = activeOverlays > 0

    @JvmStatic
    @JvmOverloads
    fun confirm(
        activity: AppCompatActivity,
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: Runnable,
        destructive: Boolean = false
    ) {
        showOverlay(activity) { dismiss ->
            DialogBody(title, message) {
                OutlinedButton(
                    onClick = dismiss,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, hairlineColor())
                ) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = { dismiss(); onConfirm.run() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (destructive) destructiveColor() else controlAccentColor(),
                        contentColor = if (destructive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                    )
                ) { Text(confirmLabel) }
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun info(activity: AppCompatActivity, title: String, message: String, onConfirm: Runnable? = null) {
        showOverlay(activity) { dismiss ->
            DialogBody(title, message) {
                Button(
                    onClick = { dismiss(); onConfirm?.run() },
                    colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = MaterialTheme.colorScheme.onPrimary)
                ) { Text(stringResource(R.string.ok)) }
            }
        }
    }

    // Single-line text input, same card/button styling as confirm(). onConfirm receives the
    // field's current text; the field starts pre-filled with initialValue (e.g. for Rename).
    // confirmLabel has no default (it would sit before the required onConfirm parameter, which
    // @JvmOverloads cannot generate a shorter overload for) — Java call sites always pass it.
    @JvmStatic
    fun prompt(
        activity: AppCompatActivity,
        title: String,
        initialValue: String,
        confirmLabel: String,
        onConfirm: Consumer<String>
    ) {
        showOverlay(activity) { dismiss ->
            var value by remember { mutableStateOf(initialValue) }
            ThemedDialogTitle(title)
            ThemedDialogScrollBody {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = dismiss,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, hairlineColor())
                ) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = { dismiss(); onConfirm.accept(value) },
                    colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = MaterialTheme.colorScheme.onPrimary)
                ) { Text(confirmLabel) }
            }
        }
    }

    // prompt() with a short explanatory line under the title (what the field accepts).
    @JvmStatic
    fun prompt(
        activity: AppCompatActivity,
        title: String,
        message: String,
        initialValue: String,
        confirmLabel: String,
        onConfirm: Consumer<String>
    ) {
        showOverlay(activity) { dismiss ->
            var value by remember { mutableStateOf(initialValue) }
            ThemedDialogTitle(title)
            ThemedDialogScrollBody {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = dismiss,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, hairlineColor())
                ) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = { dismiss(); onConfirm.accept(value) },
                    colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = MaterialTheme.colorScheme.onPrimary)
                ) { Text(confirmLabel) }
            }
        }
    }

    // One option among several, e.g. Replace/Rename for a paste conflict. destructive tints the
    // row's label with the theme's error color (e.g. an overwrite that can't be undone).
    // destructive is last (default) so @JvmOverloads can offer a 2-arg overload from Java.
    class Option @JvmOverloads constructor(
        val label: String,
        val onClick: Runnable,
        val destructive: Boolean = false
    )

    // Message plus a stack of full-width option rows, with a separate Cancel button below —
    // for choices that aren't a plain yes/no (e.g. "File Conflict": Replace / Rename / Cancel).
    @JvmStatic
    @JvmOverloads
    fun choice(
        activity: AppCompatActivity,
        title: String,
        message: String,
        options: List<Option>,
        cancelLabel: String = activity.getString(R.string.cancel)
    ) {
        showOverlay(activity) { dismiss ->
            ThemedDialogTitle(title)
            ThemedDialogScrollBody(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                options.forEach { option ->
                    Surface(
                        onClick = { dismiss(); option.onClick.run() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = WinZShapes.Medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (option.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    ) {
                        Box(Modifier.padding(vertical = 12.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(option.label, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.align(Alignment.End)) {
                OutlinedButton(
                    onClick = dismiss,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, hairlineColor())
                ) { Text(cancelLabel) }
            }
        }
    }

    // One row in an actions() menu — the themed replacement for a native PopupMenu item.
    // iconRes is a drawable resource id (0 for none), same convention as the rest of this app's
    // Java call sites (R.drawable.icon_open, android.R.drawable.ic_menu_agenda, etc). The two
    // defaulted params are last so @JvmOverloads can offer 2/3/4-arg overloads from Java.
    class ActionItem @JvmOverloads constructor(
        val label: String,
        val onClick: Runnable,
        @DrawableRes val iconRes: Int = 0,
        val destructive: Boolean = false
    )

    // Translucent card listing tappable rows — same surface/border as every other dialog here,
    // used where the app previously showed a plain android.widget.PopupMenu (e.g. per-file
    // Run/Copy/Cut/Rename/Delete actions in the file manager). title has no default (it precedes
    // the required items list) — Java call sites pass null explicitly when there's no title.
    @JvmStatic
    fun actions(activity: AppCompatActivity, title: String?, items: List<ActionItem>) {
        showOverlay(activity) { dismiss ->
            // With a title the divider sits under it (like every dialog); the rows are then
            // not split by lines. Untitled menus keep the lines between rows.
            if (title != null) ThemedDialogTitle(title)
            // Scrolls when the list is longer than the screen (e.g. many containers in landscape).
            ThemedDialogScrollBody {
                items.forEachIndexed { index, item ->
                    Surface(
                        onClick = { dismiss(); item.onClick.run() },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.Transparent,
                        contentColor = if (item.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (item.iconRes != 0) {
                                Icon(
                                    painterResource(id = item.iconRes),
                                    null,
                                    tint = if (item.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    // Fixed 24dp like every other menu icon; without it the icon
                                    // takes the drawable's own intrinsic size (varies per asset).
                                    modifier = Modifier.padding(end = 14.dp).size(24.dp)
                                )
                            }
                            Text(item.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (title == null && index != items.lastIndex) {
                        HorizontalDivider(color = dividerColor())
                    }
                }
            }
        }
    }

    // Scrollable checkbox list plus Cancel/confirmLabel buttons, e.g. picking which downloadable
    // input-control profiles to fetch. entries has no default (it precedes the required
    // onConfirm parameter) — Java call sites always pass it. Capped at ~5 visible rows
    // (heightIn) so a long server-provided list doesn't push the buttons off-card.
    // initiallyChecked is trailing (defaults to all-unchecked) so @JvmOverloads keeps every
    // existing 5-arg call site (a fresh pick-from-scratch list) compiling unchanged; pass it
    // when the dialog needs to reopen showing the current on/off state (e.g. per-slot toggles).
    @JvmStatic
    @JvmOverloads
    fun multiChoice(
        activity: AppCompatActivity,
        title: String,
        entries: List<String>,
        confirmLabel: String,
        onConfirm: Consumer<List<Int>>,
        initiallyChecked: List<Boolean> = emptyList()
    ) {
        showOverlay(activity) { dismiss ->
            ThemedDialogTitle(title)
            val checked = remember {
                mutableStateListOf(*Array(entries.size) { i -> initiallyChecked.getOrElse(i) { false } })
            }
            // weight(fill = false): the list gets only the height left after the title and the
            // buttons, so on a landscape phone it shrinks (and scrolls) instead of pushing the
            // buttons off the card. heightIn still caps it on tall screens.
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 280.dp)) {
                items(entries.size) { index ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { checked[index] = !checked[index] }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked[index],
                            onCheckedChange = { checked[index] = it },
                            colors = CheckboxDefaults.colors(checkedColor = controlAccentColor())
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(entries[index], style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = dismiss,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, hairlineColor())
                ) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = {
                        dismiss()
                        onConfirm.accept(checked.indices.filter { checked[it] })
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = MaterialTheme.colorScheme.onPrimary)
                ) { Text(confirmLabel) }
            }
        }
    }

    private fun showOverlay(activity: AppCompatActivity, content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        lateinit var composeView: ComposeView
        composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZOverlayTheme {
                    // Animates in/out like a real Dialog window (e.g. ThemedDialog's reinstall-
                    // imagefs confirm) instead of the ComposeView just appearing/disappearing
                    // instantly. currentState only catches up to targetState once the exit
                    // animation actually finishes — that's the signal it's safe to detach.
                    val visibleState = remember { MutableTransitionState(false) }
                    LaunchedEffect(Unit) { visibleState.targetState = true }
                    LaunchedEffect(visibleState.currentState) {
                        if (!visibleState.currentState && !visibleState.targetState) {
                            (composeView.parent as? ViewGroup)?.removeView(composeView)
                        }
                    }
                    val dismiss: () -> Unit = { visibleState.targetState = false }
                    // System back closes this overlay instead of reaching the screen behind it
                    // (it lives in the activity's own view tree, not a Dialog window).
                    BackHandler(enabled = visibleState.targetState) { dismiss() }
                    AnimatedVisibility(
                        visibleState = visibleState,
                        enter = WinZMotion.dialogScrimEnter(),
                        exit = WinZMotion.dialogScrimExit()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(DialogScrimColor)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = dismiss
                                )
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedVisibility(
                                visibleState = visibleState,
                                enter = WinZMotion.dialogCardEnter(),
                                exit = WinZMotion.dialogCardExit()
                            ) {
                                ThemedDialogSurface(modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}) {
                                    content(dismiss)
                                }
                            }
                        }
                    }
                }
            }
        }
        composeView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                activeOverlays++
            }

            override fun onViewDetachedFromWindow(v: android.view.View) {
                v.removeOnAttachStateChangeListener(this)
                if (activeOverlays > 0) activeOverlays--
            }
        })
        root.addView(
            composeView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }
}

@Composable
private fun ColumnScope.DialogBody(
    title: String,
    message: String,
    buttons: @Composable () -> Unit
) {
    ThemedDialogTitle(title)
    ThemedDialogScrollBody {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(18.dp))
    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        buttons()
    }
}
