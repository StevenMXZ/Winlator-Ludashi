package com.winlator.cmod.ui.onboarding

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ClassicCoreStatusCard(readyState: State<Boolean>, progressState: State<Int>, retry: () -> Unit) {
    val ready = readyState.value
    val progress = progressState.value
    Surface(
        onClick = { if (!ready && progress == 0) retry() },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(.90f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ready) {
                Icon(Icons.Outlined.Check, null, Modifier.size(32.dp))
            } else {
                CircularProgressIndicator(
                    progress = progress / 100f,
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeWidth = 5.dp
                )
            }
            Spacer(Modifier.size(14.dp))
            Text(
                if (ready) uiStr(R.string.ui_winlator_core_is_ready) else uiStr(R.string.ui_preparing_winlator_core_in_the_background),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
