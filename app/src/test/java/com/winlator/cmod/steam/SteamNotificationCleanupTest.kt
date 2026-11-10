package com.winlator.cmod.steam

import android.app.Notification
import android.app.NotificationManager
import android.app.NotificationChannel
import android.content.Context
import com.winlator.cmod.MainActivity
import com.winlator.cmod.services.NotificationService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SteamNotificationCleanupTest {
    @Test fun appNotificationExposesTheSharedExitAction() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(MainActivity.NOTIFICATION_CHANNEL_ID, "Winlator", NotificationManager.IMPORTANCE_LOW))
        val controller = Robolectric.buildService(NotificationService::class.java).create().startCommand(0, 1)
        try {
            val notification = shadowOf(manager).getNotification(MainActivity.NOTIFICATION_ID)
            assertNotNull(notification)
            assertEquals(NotificationService.exitPendingIntent(context), notification.actions.single().actionIntent)
            val intent = shadowOf(notification.actions.single().actionIntent).savedIntent
            assertEquals(NotificationService.ACTION_EXIT, intent.action)
        } finally { controller.destroy() }
    }
    @Test fun steamForegroundUsesSteamIconAndRemovesItOnDestroy() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val controller = Robolectric.buildService(SteamService::class.java).create()
        val notification = shadowOf(manager).getNotification(SteamService.NOTIFICATION_ID)
        assertNotNull(notification)
        assertEquals(com.winlator.cmod.R.drawable.ic_steam, notification.smallIcon.resId)
        controller.destroy()
        assertNull(shadowOf(manager).getNotification(SteamService.NOTIFICATION_ID))
    }
    @Test fun shutdownRemovesSteamNotificationEvenWithoutRunningService() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("steam", "Steam", NotificationManager.IMPORTANCE_LOW))
        manager.notify(SteamService.NOTIFICATION_ID, Notification.Builder(context, "steam").setSmallIcon(com.winlator.cmod.R.drawable.ic_steam).build())
        assertNotNull(shadowOf(manager).getNotification(SteamService.NOTIFICATION_ID))
        SteamService.shutdown(context)
        assertNull(shadowOf(manager).getNotification(SteamService.NOTIFICATION_ID))
    }
}
