package com.winlator.cmod.ui

import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.winlator.cmod.ui.bindOverlayOwners
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared destructive action UI; filesystem work stays off the UI thread. */
object ConfirmActionDialog {
    @JvmStatic fun show(fragment: Fragment, title: String, message: String, confirm: String, action: Runnable, success: Runnable) {
        val activity = fragment.requireActivity()
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val tag = "confirm-destructive-action"
        if (root.findViewWithTag<android.view.View>(tag) != null) return
        val view = ComposeView(activity).apply { bindOverlayOwners(activity); this.tag = tag; setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed) }
        val close = { view.disposeComposition(); root.removeView(view) }
        view.setContent { WinZTheme {
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            AlertDialog(onDismissRequest = { if (!busy) close() }, icon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                title = { Text(title) }, text = {
                    val local = androidx.compose.ui.platform.LocalView.current
                    DisposableEffect(local) {
                        val window = (local.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                        window?.decorView?.systemUiVisibility = activity.window.decorView.systemUiVisibility
                        if (activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN != 0)
                            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                        onDispose { }
                    }
                    Column { Text(message); if (busy) LinearProgressIndicator(); if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) } },
                confirmButton = { Button(enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError), onClick = {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { action.run() } }
                        busy = false
                        result.onSuccess { close(); if (fragment.isAdded) success.run() }.onFailure { error = it.message.orEmpty() }
                    }
                }) { Text(confirm) } }, dismissButton = { TextButton(enabled = !busy, onClick = close) { Text(uiStr(R.string.steam_ui_cancel)) } })
        } }
        root.addView(view)
    }
}
