package com.winlator.cmod.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R

fun applyAppFullscreen(activity: Activity?) {
    if (activity == null) return

    val window = activity.window
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    WindowInsetsControllerCompat(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

@Composable
fun KeepLandscapeChromeHidden(activity: MainActivity?, restoreChromeOnPortrait: Boolean = true) {
    DisposableEffect(activity, restoreChromeOnPortrait) {
        val toolbar = activity?.findViewById<View>(R.id.Toolbar)
        val bottomNavigation = activity?.findViewById<View>(R.id.BottomNavigation)
        val drawer = activity?.findViewById<DrawerLayout>(R.id.DrawerLayout)
        val decor = activity?.window?.decorView

        fun forceLandscapeChrome() {
            if (activity?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                toolbar?.visibility = View.GONE
                bottomNavigation?.visibility = View.GONE
                drawer?.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            }
        }

        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            forceLandscapeChrome()
            true
        }

        applyAppFullscreen(activity)
        forceLandscapeChrome()
        decor?.viewTreeObserver?.addOnPreDrawListener(preDrawListener)

        onDispose {
            if (decor?.viewTreeObserver?.isAlive == true) {
                decor.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            }
            if (restoreChromeOnPortrait &&
                activity?.resources?.configuration?.orientation != Configuration.ORIENTATION_LANDSCAPE
            ) {
                toolbar?.visibility = View.VISIBLE
                bottomNavigation?.visibility = View.VISIBLE
                drawer?.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            }
        }
    }

    LaunchedEffect(activity) {
        applyAppFullscreen(activity)
        activity?.setBottomNavigationVisible(false)
        activity?.setMainToolbarVisible(false)
        withFrameNanos { }
        applyAppFullscreen(activity)
        activity?.setBottomNavigationVisible(false)
        activity?.setMainToolbarVisible(false)
    }
}

@Composable
fun LandscapeMainNavigation(
    activity: MainActivity?,
    selected: Int,
    title: String,
    modifier: Modifier = Modifier,
    actionIcon: ImageVector? = null,
    actionDescription: String = "Action",
    onAction: (() -> Unit)? = null
) {
    KeepLandscapeChromeHidden(activity)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(start = 20.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.weight(1f))
        if (actionIcon != null && onAction != null) {
            Destination(actionIcon, actionDescription, false, onAction)
        }
        Destination(Icons.Outlined.Home, uiStr(R.string.library), selected == R.id.main_menu_shortcuts) {
            activity?.navigateToMainDestination(R.id.main_menu_shortcuts)
        }
        Destination(Icons.Outlined.Dns, uiStr(R.string.containers), selected == R.id.main_menu_containers) {
            activity?.navigateToMainDestination(R.id.main_menu_containers)
        }
        Destination(Icons.Outlined.SportsEsports, uiStr(R.string.input_controls), selected == R.id.main_menu_input_controls) {
            activity?.navigateToMainDestination(R.id.main_menu_input_controls)
        }
        Destination(Icons.Outlined.Settings, uiStr(R.string.settings), selected == R.id.main_menu_settings) {
            activity?.navigateToMainDestination(R.id.main_menu_settings)
        }
    }
}

@Composable
private fun Destination(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val whiteTheme = MaterialTheme.colorScheme.background.luminance() > .65f
    val normalIcon = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onBackground.copy(alpha = .68f)
    val iconColor = if (whiteTheme) Color.White else normalIcon
    val selectedBackground = MaterialTheme.colorScheme.primary.copy(alpha = .12f)
    val background = if (whiteTheme) {
        Color.Black.copy(alpha = if (selected) .90f else .78f)
    } else if (selected) selectedBackground else Color.Transparent

    Surface(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 3.dp),
        shape = RoundedCornerShape(12.dp),
        color = background,
        contentColor = iconColor
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(icon, description, modifier = Modifier.size(23.dp))
        }
    }
}
