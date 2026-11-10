package com.winlator.cmod.ui

import com.winlator.cmod.R
import androidx.compose.ui.res.stringResource
import com.winlator.cmod.ui.theme.DialogScrimColor
import com.winlator.cmod.ui.theme.tabularNumbers
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.pointer.pointerInput
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.ThemedDialogSurface
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.hairlineColor
import com.winlator.cmod.ui.theme.dividerColor

// Themed replacement for DownloadProgressDialog's internals — a compact circular-progress +
// message card, with an optional Cancel row, instead of a plain android.app.Dialog inflating
// download_progress_dialog.xml. DownloadProgressDialog.java keeps its exact public API and just
// forwards to this object, so its callers (ImageFS reinstall, wine/driver asset extraction via
// ImageFsInstaller, general downloads via HttpUtils/AdrenotoolsManager) don't change at all.
//
// Takes a plain Activity, not AppCompatActivity: DownloadProgressDialog's own constructor and
// HttpUtils.download() are already typed that way, and android.R.id.content/ComposeView don't
// need anything AppCompat-specific.
object ThemedDownloadProgressHost {
    private data class ProgressState(val message: String, val percent: Int)

    @JvmStatic
    fun show(activity: Activity, message: String, onCancel: Runnable?): View {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val state = mutableStateOf(ProgressState(message, 0))
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        progress = { animatedProgress },
                                        modifier = Modifier.size(60.dp),
                                        color = controlAccentColor(),
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        strokeWidth = 5.dp
                                    )
                                    Text(
                                        "${progress.percent}%",
                                        style = MaterialTheme.typography.labelLarge.tabularNumbers(),
                                        fontWeight = FontWeight.Bold,
                                        color = controlAccentColor()
                                    )
                                }
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    progress.message,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (onCancel != null) {
                                Spacer(Modifier.height(16.dp))
                                HorizontalDivider(color = dividerColor())
                                Spacer(Modifier.height(12.dp))
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
        }
        root.addView(
            composeView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        return composeView
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun setProgress(view: View?, percent: Int) {
        (view?.tag as? MutableState<ProgressState>)?.let { it.value = it.value.copy(percent = percent.coerceIn(0, 100)) }
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun setMessage(view: View?, message: String) {
        (view?.tag as? MutableState<ProgressState>)?.let { it.value = it.value.copy(message = message) }
    }

    @JvmStatic
    fun dismiss(view: View?) {
        if (view == null) return
        (view.parent as? ViewGroup)?.removeView(view)
    }

    @JvmStatic
    fun isShowing(view: View?): Boolean = view != null && view.parent != null
}
