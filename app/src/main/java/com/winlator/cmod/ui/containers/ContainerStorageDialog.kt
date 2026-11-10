package com.winlator.cmod.ui.containers

import android.text.format.Formatter
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.winlator.cmod.container.Container
import com.winlator.cmod.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ContainerStorageDialog {
    internal const val VIEW_TAG = "container_storage_overlay"

    @JvmStatic fun dismiss(activity: AppCompatActivity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val host = root.findViewWithTag<ComposeView>(VIEW_TAG) ?: return
        root.removeView(host)
        // Release the composition immediately, including its input and back handlers.
        host.disposeComposition()
    }

    @JvmStatic fun show(activity: AppCompatActivity, container: Container) {
        show(activity, container.name, container.rootDir)
    }

    internal fun show(activity: AppCompatActivity, containerName: String, containerRoot: File) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        if (root.findViewWithTag<ComposeView>(VIEW_TAG) != null) return
        val host = ComposeView(activity).apply {
            tag = VIEW_TAG
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                WinZOverlayTheme {
                    BackHandler { dismiss(activity) }
                    Box(Modifier.fillMaxSize().background(DialogScrimColor)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { dismiss(activity) }
                        .padding(24.dp), contentAlignment = Alignment.Center) {
                        // This is one inline overlay. A nested Dialog window would outlive
                        // a detached ComposeView and keep intercepting the app's input.
                        ThemedDialogSurface(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(120).dp)
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}) {
                            ThemedDialogTitle(containerName)
                            var sizes by remember { mutableStateOf<StorageUsage?>(null) }
                            LaunchedEffect(containerRoot) {
                                sizes = withContext(Dispatchers.IO) { ContainerStorageUsage.measure(containerRoot) }
                            }
                            ThemedDialogScrollBody(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (sizes == null) CircularProgressIndicator(Modifier.size(28.dp))
                                else listOf("Drive C:" to sizes!!.driveC, "Cache" to sizes!!.cache, "Total" to sizes!!.total).forEach { (label, bytes) ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(label)
                                        Text(Formatter.formatFileSize(activity, bytes))
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton({ dismiss(activity) }, Modifier.align(Alignment.End)) {
                                Text(activity.getString(com.winlator.cmod.R.string.ui_close))
                            }
                        }
                    }
                }
            }
        }
        root.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
