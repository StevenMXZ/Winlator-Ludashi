package com.winlator.cmod.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinlatorThemeManager
import com.winlator.cmod.ui.theme.WinlatorShapes
import com.winlator.cmod.ui.theme.WinlatorTypography
import com.winlator.cmod.ui.theme.winlatorColorScheme

/** A themed dialog window without the full-screen background of the activity theme. */
internal object AppInfoDialog {
    fun show(activity: Activity, onDismiss: () -> Unit = {}, content: @Composable (() -> Unit) -> Unit) {
        val dialog = ComponentDialog(activity)
        UiStrings.install(activity)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { onDismiss() }
        dialog.setContentView(ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme(
                    colorScheme = winlatorColorScheme(WinlatorThemeManager.currentTheme()),
                    typography = WinlatorTypography,
                    shapes = WinlatorShapes
                ) {
                    val config = LocalConfiguration.current
                    Surface(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(max = (config.screenHeightDp - 48).coerceAtLeast(120).dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) { content(dialog::dismiss) }
                }
            }
        })
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        val metrics = activity.resources.displayMetrics
        val screenWidth = (activity.resources.configuration.screenWidthDp * metrics.density).toInt()
        val width = minOf((440 * metrics.density).toInt(),
            (screenWidth - 32 * metrics.density).toInt().coerceAtLeast(1))
        dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }
}
