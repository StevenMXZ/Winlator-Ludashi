package com.winlator.cmod.stores

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import java.util.Locale

internal fun remainingTime(done: Long, total: Long, speed: Long): Long? =
    if (speed > 0 && total > done) (total - done + speed - 1) / speed else null

@Composable internal fun StoreTransferStatistics(done: Long, total: Long, speed: Long) {
    val eta = remainingTime(done, total, speed)
    val time = eta?.let { seconds -> if (seconds >= 3600) "%dh %02dm".format(seconds / 3600, seconds % 3600 / 60)
        else if (seconds >= 60) "%dm %02ds".format(seconds / 60, seconds % 60) else "${seconds}s" }
    Text(uiStr(R.string.store_transfer_rate, String.format(Locale.getDefault(), "%.1f", speed / 1048576.0), time ?: uiStr(R.string.store_estimating)), style = MaterialTheme.typography.bodySmall)
}
