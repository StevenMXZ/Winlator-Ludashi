package com.winlator.cmod.steam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinZTheme

/** Static store artwork: no animation, timers or decoded background bitmaps. */
@Composable internal fun StoreTheme(content: @Composable () -> Unit) {
    WinZTheme {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
            primary = Color(0xFF338FFF), onPrimary = Color.White,
            primaryContainer = Color(0xFF164C85), onPrimaryContainer = Color.White,
            background = Color(0xFF090F19), onBackground = Color(0xFFF1F5FC),
            surface = Color(0xFF111A27), onSurface = Color(0xFFF1F5FC),
            surfaceVariant = Color(0xFF182332), onSurfaceVariant = Color(0xFFB6C1D1),
            outline = Color(0xFF495568), outlineVariant = Color(0xFF293748)
        ), content = content)
    }
}

internal fun Modifier.storeBackground() = background(Brush.linearGradient(
    listOf(Color(0xFF142C49), Color(0xFF090F19), Color(0xFF090F19), Color(0xFF103963))))

@Composable internal fun StoreSection(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .86f))) {
        content()
    }
}
