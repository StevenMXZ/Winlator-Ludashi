package com.winlator.cmod.services;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.core.ProcessHelper;

import java.util.concurrent.atomic.AtomicBoolean;

public class NotificationService extends Service {
    private static final String TAG = "WinlatorNotification";
    public static final String ACTION_EXIT = "com.winlator.cmod.action.EXIT";
    private static boolean isRunning = false;
    private final AtomicBoolean exiting = new AtomicBoolean(false);
    public static PowerManager.WakeLock wakeLock = null;

    public static boolean isRunning() {
        return isRunning;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Handle Exit before checking the notification permission. The action must still
        // work when the user has disabled notifications after the service started.
        if (intent != null && ACTION_EXIT.equals(intent.getAction())) {
            exitApplication();
            return START_NOT_STICKY;
        }
        if (exiting.get()) return START_NOT_STICKY;

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Intent launchIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent exitPendingIntent = exitPendingIntent(this);

        Notification notification = new NotificationCompat.Builder(this, MainActivity.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.winlator_mark)
                .setContentTitle("Winlator")
                .setContentText("Winlator is running")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentIntent)
                .addAction(R.drawable.icon_exit, "Exit", exitPendingIntent)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(true)
                .build();

        startForeground(MainActivity.NOTIFICATION_ID, notification);
        isRunning = true;

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null && wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Winlator::KeepAlive");
        }
        return START_NOT_STICKY;
    }

    public static PendingIntent exitPendingIntent(android.content.Context context) {
        return PendingIntent.getService(context, 1,
                new Intent(context, NotificationService.class).setAction(ACTION_EXIT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void exitApplication() {
        if (!exiting.compareAndSet(false, true)) return;

        com.winlator.cmod.steam.SteamService.shutdown(this);
        androidx.core.app.NotificationManagerCompat.from(this).cancel(com.winlator.cmod.XServerDisplayActivity.NOTIFICATION_ID);
        // Do not block the service main thread while terminating Wine processes.
        new Thread(() -> {
            try {
                ProcessHelper.terminateAllWineProcesses();
                long deadline = SystemClock.uptimeMillis() + 1500;
                while (SystemClock.uptimeMillis() < deadline
                        && !ProcessHelper.listRunningWineProcesses().isEmpty()) {
                    Thread.sleep(50);
                }
                ProcessHelper.killAllWineProcesses();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ProcessHelper.killAllWineProcesses();
            } catch (Exception e) {
                Log.w(TAG, "Unable to stop all Wine processes cleanly", e);
            } finally {
                // Remove the persistent notification and all Winlator tasks before
                // terminating the app process, including when a game is active.
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (manager != null) {
                    for (ActivityManager.AppTask task : manager.getAppTasks()) {
                        task.finishAndRemoveTask();
                    }
                }
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, "WinlatorNotificationExit").start();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        com.winlator.cmod.steam.SteamService.shutdown(this);
        androidx.core.app.NotificationManagerCompat.from(this).cancel(com.winlator.cmod.XServerDisplayActivity.NOTIFICATION_ID);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        isRunning = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
