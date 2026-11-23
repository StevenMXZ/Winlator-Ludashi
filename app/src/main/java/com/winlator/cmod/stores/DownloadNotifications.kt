package com.winlator.cmod.stores

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.winlator.cmod.R
import com.winlator.cmod.steam.SteamActivity
import com.winlator.cmod.ui.uiStr
import java.util.concurrent.ConcurrentHashMap

internal object DownloadNotifications {
    private val last = ConcurrentHashMap<Int, Long>()
    fun channel(context: Context, id: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(id, uiStr(R.string.ludashi_download_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = uiStr(R.string.ludashi_download_channel_help)
        })
    }
    fun downloadsIntent(context: Context, store: String): Intent =
        if (store == "Steam") Intent(context, SteamActivity::class.java).putExtra("steam_tab", 1)
            else if (store == "GOG") Intent(context, com.winlator.cmod.gog.GogActivity::class.java).putExtra("gog_tab", 1)
            else Intent(context, StoreActivity::class.java).putExtra("store", "itch").putExtra("tab", 1)

    private fun openDownloads(context: Context, store: String): PendingIntent {
        val open = downloadsIntent(context, store)
        return PendingIntent.getActivity(context, if (store == "Steam") 310 else if (store == "GOG") 311 else 312, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    fun build(context: Context, channel: String, store: String, game: String, done: Long = 0, total: Long = 0, stage: String = "", speed: Long? = null): Notification {
        val detail = buildList {
            if (game.isNotBlank()) add(game)
            if (stage.isNotBlank()) add(stage)
            if (total > 0) add("${progress(done, total) / 10}%")
            if (speed != null) add(transferTimingText(done, total, speed))
        }.joinToString(" · ")
        return Notification.Builder(context, channel).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Ludashi · $store").setSubText(uiStr(R.string.ludashi_downloads))
            .setContentText(detail).setStyle(Notification.BigTextStyle().bigText(detail)).setContentIntent(openDownloads(context, store))
            .setOnlyAlertOnce(true).setOngoing(true)
            .apply { if (total > 0) setProgress(1000, progress(done, total), false) else setProgress(0, 0, true) }
            .build()
    }
    fun progress(done: Long, total: Long): Int = if (total <= 0) 0 else (done.toDouble() / total * 1000).toInt().coerceIn(0, 1000)
    fun update(context: Context, id: Int, channel: String, store: String, game: String, done: Long, total: Long, stage: String, force: Boolean = false, speed: Long? = null) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - (last[id] ?: -2000L) < 1000) return
        last[id] = now
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, build(context, channel, store, game, done, total, stage, speed)) }
    }

    fun completed(context: Context, id: Int, channel: String, store: String, game: String) {
        // A separate notification survives removal of the service's progress notification.
        runCatching {
            val notification = Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_download_complete)
                .setContentTitle(game)
                .setContentText(context.getString(R.string.store_download_complete))
                .setSubText("Ludashi · $store")
                .setContentIntent(openDownloads(context, store))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify("download-complete", id, notification)
        }
    }
}
