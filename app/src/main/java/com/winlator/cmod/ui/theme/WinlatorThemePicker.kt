package com.winlator.cmod.ui.theme

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun WinlatorThemeChoices(
    selected: WinlatorThemeType,
    onSelected: (WinlatorThemeType) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        WinlatorThemeType.values().forEach { theme ->
            ThemeChoiceRow(
                theme = theme,
                selected = theme == selected,
                onClick = { onSelected(theme) }
            )
        }
    }
}

@Composable
private fun ThemeChoiceRow(
    theme: WinlatorThemeType,
    selected: Boolean,
    onClick: () -> Unit
) {
    val preview = winlatorThemePreview(theme)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.70f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        tonalElevation = 0.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThemePreview(preview)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(theme.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    theme.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun ThemePreview(colors: ThemePreviewColors) {
    Surface(
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(12.dp),
        color = colors.background,
        border = BorderStroke(1.dp, colors.outline)
    ) {
        Box(Modifier.padding(8.dp)) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(18.dp)
                    .background(colors.surface, RoundedCornerShape(5.dp))
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(15.dp)
                    .background(colors.accent, CircleShape)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WinlatorThemePreferenceCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val current = WinlatorThemeManager.currentTheme()
    var expanded by remember { mutableStateOf(false) }

    Surface(
        onClick = { expanded = true },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Palette, contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(uiStr(R.string.theme), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(current.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (expanded) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(uiStr(R.string.ui_text_41def7a0febe), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    uiStr(R.string.ui_choose_the_winlator_theme_changes_are_applied_immediately),
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.size(12.dp))
                WinlatorThemeChoices(
                    selected = current,
                    onSelected = { WinlatorThemeManager.setTheme(context, it) }
                )
            }
        }
    }
}

internal data class ThemePreviewColors(
    val background: Color,
    val surface: Color,
    val accent: Color,
    val outline: Color
)

internal fun winlatorThemePreview(theme: WinlatorThemeType): ThemePreviewColors = when (theme) {
    WinlatorThemeType.WHITE -> ThemePreviewColors(
        background = Color(0xFFF5F6F8), surface = Color.White,
        accent = Color(0xFF25272D), outline = Color(0xFFB9BBC2)
    )
    WinlatorThemeType.BLACK -> ThemePreviewColors(
        background = Color(0xFF06070A), surface = Color(0xFF17181E),
        accent = Color(0xFFF2F2F4), outline = Color(0xFF41434D)
    )
    WinlatorThemeType.AMOLED -> ThemePreviewColors(
        background = Color.Black, surface = Color(0xFF080808),
        accent = Color.White, outline = Color(0xFF2A2A2A)
    )
    WinlatorThemeType.BLUE -> ThemePreviewColors(
        background = Color(0xFF050A12), surface = Color(0xFF101B2B),
        accent = Color(0xFF82B8FF), outline = Color(0xFF355174)
    )
    WinlatorThemeType.RED -> ThemePreviewColors(
        background = Color(0xFF0C0607), surface = Color(0xFF211113),
        accent = Color(0xFFFF8A8F), outline = Color(0xFF6E353A)
    )
    WinlatorThemeType.PURPLE -> ThemePreviewColors(
        background = Color(0xFF0E0A13), surface = Color(0xFF241C2D),
        accent = Color(0xFFD0BCFF), outline = Color(0xFF675A78)
    )
}
