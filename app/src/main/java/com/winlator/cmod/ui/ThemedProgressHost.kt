package com.winlator.cmod.ui

import com.winlator.cmod.R
import androidx.compose.ui.res.stringResource
import com.winlator.cmod.ui.theme.DialogScrimColor
import com.winlator.cmod.ui.theme.tabularNumbers
import com.winlator.cmod.ui.theme.ThemedDialogTitle
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.pointer.pointerInput
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import com.winlator.cmod.ui.theme.WinZMotion
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.ThemedDialogSurface
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.hairlineColor

// Themed replacement for the file manager's copy/move progress dialog, which used to be a plain
// android.app.AlertDialog wrapping a manually-built LinearLayout (ProgressBar + two TextViews).
// Unlike ThemedAlertHost's dialogs (shown once, then dismiss themselves), this one is shown once
// and pushed new percent/status values many times while a background thread runs — so, unlike
// ThemedAlertHost, this returns the created View and exposes separate update()/dismiss() calls
// that the Java side keeps and drives from its Handler.post progress callback.
object ThemedProgressHost {
    private data class ProgressState(val percent: Int, val statusText: String)

    // Not cancelable by tapping outside (mirrors the old dialog's setCancelable(false)) — only
    // the Cancel button, wired to onCancel, closes it early.
    @JvmStatic
    fun show(activity: AppCompatActivity, title: String, onCancel: Runnable): View {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val state = mutableStateOf(ProgressState(0, activity.getString(R.string.ui_calculating)))
        lateinit var composeView: ComposeView
        composeView = ComposeView(activity).apply {
            tag = state
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZOverlayTheme {
                    BackHandler { }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(DialogScrimColor).pointerInput(Unit) { },
                        contentAlignment = Alignment.Center
                    ) {
                        ThemedDialogSurface {
                            val progress = state.value
                            val animatedProgress by animateFloatAsState(progress.percent / 100f, WinZMotion.standard(150), label = "downloadProgress")
                            ThemedDialogTitle(title)
                            Text(
                                "${progress.percent}%",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.titleMedium.tabularNumbers(),
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp),
                                color = controlAccentColor(),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                progress.statusText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(18.dp))
                            Row(Modifier.align(Alignment.End)) {
                                OutlinedButton(
                                    onClick = {
                                        (composeView.parent as? ViewGroup)?.removeView(composeView)
                                        onCancel.run()
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                                    border = BorderStroke(1.dp, hairlineColor())
                                ) { Text(stringResource(R.string.cancel)) }
                            }
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

    // Called from Java's background-thread progress callback (already hopped onto the main
    // thread via Handler.post, same as the old updateProgress()).
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun update(view: View?, percent: Int, statusText: String) {
        (view?.tag as? MutableState<ProgressState>)?.value = ProgressState(percent, statusText)
    }

    @JvmStatic
    fun dismiss(view: View?) {
        if (view == null) return
        (view.parent as? ViewGroup)?.removeView(view)
    }
}
