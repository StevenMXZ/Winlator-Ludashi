package com.winlator.cmod.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

// Motion and component geometry adapted from teldommm/WinLite. Ludashi's six palettes
// remain the source of every control colour; no fixed WinLite blue is introduced.
@Composable fun controlAccentColor(): Color = MaterialTheme.colorScheme.primary
@Composable fun destructiveColor(): Color = MaterialTheme.colorScheme.error
@Composable fun hairlineColor(): Color = MaterialTheme.colorScheme.outlineVariant
@Composable fun dividerColor(): Color = hairlineColor().copy(alpha = 0.55f)
@Composable fun sidebarCardFillColor(): Color = MaterialTheme.colorScheme.surfaceVariant
@Composable fun sidebarRailHighlightColor(): Color = MaterialTheme.colorScheme.primaryContainer
@Composable fun accentSwitchColors() = SwitchDefaults.colors()
@Composable fun WinZOverlayTheme(content: @Composable () -> Unit) {
    com.winlator.cmod.ui.UiStrings.install(androidx.compose.ui.platform.LocalContext.current)
    MaterialTheme(colorScheme = winlatorColorScheme(WinlatorThemeManager.currentTheme()), typography = WinlatorTypography, shapes = WinlatorShapes, content = content)
}
object WinZShapes {
    val ExtraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
    val Small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val Medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    val Large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    val ExtraLarge = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
}
val DialogScrimColor = Color.Black.copy(alpha = 0.5f)
@Composable fun isCompactDialogHeight() = LocalConfiguration.current.screenHeightDp < 480
@Composable fun ThemedDialog(onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest) { ThemedDialogSurface(modifier, content) }
}
@Composable fun ThemedDialogSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val alpha by animateFloatAsState(if (entered) 1f else 0f, WinZMotion.enter(160), label = "dialogAlpha")
    val scale by animateFloatAsState(if (entered) 1f else .96f, WinZMotion.enter(160), label = "dialogScale")
    Surface(modifier.widthIn(max = if (isCompactDialogHeight()) 560.dp else 420.dp).graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale },
        shape = WinZShapes.Large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

@Composable fun ThemedDialogTitle(text: String, leading: (@Composable () -> Unit)? = null) {
    if (text.isBlank() && leading == null) return
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        leading?.invoke()
        if (leading != null) Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = dividerColor())
}
@Composable fun ColumnScope.ThemedDialogScrollBody(modifier: Modifier = Modifier, verticalArrangement: Arrangement.Vertical = Arrangement.Top, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = verticalArrangement, content = content)
}

fun androidx.compose.ui.text.TextStyle.tabularNumbers() = copy(fontFeatureSettings = "tnum")
