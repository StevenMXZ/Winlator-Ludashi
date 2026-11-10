package com.winlator.cmod.ui.toast

import com.winlator.cmod.ui.theme.WinZMotion
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor

@Composable
internal fun WinToastContent(
    text: String,
    icon: Bitmap?,
    visible: Boolean,
) {
    WinZOverlayTheme {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(WinZMotion.enter(WinZMotion.DurationM)) + slideInVertically(WinZMotion.enter(WinZMotion.DurationM)) { it / 4 },
            exit = fadeOut(WinZMotion.exit(WinZMotion.DurationS)) + slideOutVertically(WinZMotion.exit(WinZMotion.DurationS)) { it / 4 },
        ) {
            // Same pill as WinLite (14dp corners, 360dp max width, 20dp icon), but the fill,
            // text and hairline come from the active Winlator theme instead of a hardcoded
            // dark color, so it follows Black / AMOLED / White like the rest of the UI.
            Row(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .clip(WinZShapes.Medium)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, hairlineColor(), WinZShapes.Medium)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .defaultMinSize(minHeight = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(20.dp).clip(WinZShapes.ExtraSmall),
                    )
                }
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}
